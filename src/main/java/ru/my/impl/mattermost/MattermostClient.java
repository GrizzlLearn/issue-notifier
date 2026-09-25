package ru.my.impl.mattermost;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.model.ChannelKeys;
import ru.my.model.JsonUtil;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-клиент для Mattermost API.
 * Конфигурация (domain, token, botId) читается из {@link AdminSettingsService}
 * при каждом вызове — изменения вступают в силу без перезапуска плагина.
 */
@Named
public class MattermostClient {

    private static final Logger log = LoggerFactory.getLogger(MattermostClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final AdminSettingsService adminSettings;
    private final HttpClient http;
    private final ExecutorService executor;

    /**
     * Кеш {@code email → channelId}. Direct-канал бот↔пользователь создаётся один раз
     * и не меняется, а без кеша каждое сообщение каждому получателю стоило двух
     * синхронных HTTP-запросов: на двадцать получателей — сорок вызовов по 10 секунд
     * таймаута на пуле из 2–4 потоков.
     * <p>
     * Кеш локальный, а не кластерный: это запоминание неизменного факта из
     * Mattermost, инвалидировать его между нодами нечем и незачем.
     */
    private final Cache<String, String> channelIds = CacheBuilder.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(12, TimeUnit.HOURS)
            .build();

    @Inject
    public MattermostClient(AdminSettingsService adminSettings) {
        this.adminSettings = adminSettings;
        // свой executor, чтобы погасить потоки при выгрузке бандла: в Java 17
        // у HttpClient нет close(), и его собственный пул держал бы
        // classloader старого плагина после каждого обновления
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mattermost-http");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(executor)
                .build();
    }

    /**
     * Возвращает id прямого канала бот↔пользователь.
     * Создаёт канал, если не существует (запрос идемпотентен).
     * Возвращает empty если пользователь с таким email не найден в Mattermost.
     */
    public Optional<String> findDirectChannelId(String email) {
        String cached = channelIds.getIfPresent(email);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> channelId = resolveDirectChannelId(email, Map.of());
        channelId.ifPresent(id -> channelIds.put(email, id));
        return channelId;
    }

    /**
     * Проверочная отправка с админ-страницы: домен, токен и botId берутся из формы.
     * Кеш каналов не используется — проверяют, как правило, другой сервер или токен,
     * и записывать результат проверки в рабочий кеш нельзя.
     */
    public void sendTest(String email, String text, Map<String, String> settings) {
        String channelId = resolveDirectChannelId(email, settings)
                .orElseThrow(() -> new MattermostException(
                        "Пользователь с email " + email + " не найден в Mattermost"));
        postMessage(value(ChannelKeys.MATTERMOST_DOMAIN, settings),
                value(ChannelKeys.MATTERMOST_TOKEN, settings), channelId, text);
    }

    /** Значение настройки: из формы, а если там пусто — из сохранённых настроек. */
    private String value(String key, Map<String, String> settings) {
        String fromForm = settings.get(key);
        return fromForm == null || fromForm.isBlank() ? adminSettings.get(key, "") : fromForm;
    }

    private Optional<String> resolveDirectChannelId(String email, Map<String, String> settings) {
        String domain = value(ChannelKeys.MATTERMOST_DOMAIN, settings);
        String token  = value(ChannelKeys.MATTERMOST_TOKEN, settings);
        String botId  = value(ChannelKeys.MATTERMOST_BOT_ID, settings);

        HttpResponse<String> userResp = get(domain, token,
                "/api/v4/users/email/" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        if (userResp.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(userResp);
        String userId = extractId(userResp.body());

        // POST /api/v4/channels/direct — идемпотентно, возвращает существующий канал
        String body = "[" + JsonUtil.jsonString(botId) + "," + JsonUtil.jsonString(userId) + "]";
        HttpResponse<String> chanResp = post(domain, token, "/api/v4/channels/direct", body);
        requireSuccess(chanResp);

        return Optional.of(extractId(chanResp.body()));
    }

    /**
     * Сбрасывает кеш канала: пользователя могли удалить или пересоздать в Mattermost,
     * и тогда сохранённый id перестаёт работать до следующего резолва.
     */
    public void forgetChannel(String email) {
        channelIds.invalidate(email);
    }

    /** Отправляет сообщение в канал. Бросает {@link MattermostException} при сбое. */
    public void sendMessage(String channelId, String text) {
        postMessage(adminSettings.get(ChannelKeys.MATTERMOST_DOMAIN, ""),
                adminSettings.get(ChannelKeys.MATTERMOST_TOKEN, ""), channelId, text);
    }

    private void postMessage(String domain, String token, String channelId, String text) {
        String body = "{\"channel_id\":" + JsonUtil.jsonString(channelId)
                + ",\"message\":" + JsonUtil.jsonString(text) + "}";
        requireSuccess(post(domain, token, "/api/v4/posts", body));
        log.debug("Сообщение отправлено в канал {}", channelId);
    }

    /** ponytail: HttpClient в Java 17 не закрывается — гасим хотя бы его пул потоков. */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    // ---- HTTP-обёртки ----

    private HttpResponse<String> get(String domain, String token, String path) {
        return execute(HttpRequest.newBuilder()
                .uri(URI.create(domain + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET().build());
    }

    private HttpResponse<String> post(String domain, String token, String path, String body) {
        return execute(HttpRequest.newBuilder()
                .uri(URI.create(domain + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpResponse<String> execute(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MattermostException("Ошибка HTTP-запроса: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MattermostException("HTTP-запрос прерван", e);
        }
    }

    private static void requireSuccess(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new MattermostException("Mattermost API вернул статус " + status + ": " + resp.body());
        }
    }

    static String extractId(String json) {
        Matcher m = ID_PATTERN.matcher(json);
        if (!m.find()) {
            throw new MattermostException("Поле 'id' не найдено в ответе: " + json);
        }
        return m.group(1);
    }

    public static class MattermostException extends RuntimeException {
        public MattermostException(String message) { super(message); }
        public MattermostException(String message, Throwable cause) { super(message, cause); }
    }
}
