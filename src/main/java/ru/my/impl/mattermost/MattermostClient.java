package ru.my.impl.mattermost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;

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
import java.util.Optional;
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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final AdminSettingsService adminSettings;

    @Inject
    public MattermostClient(AdminSettingsService adminSettings) {
        this.adminSettings = adminSettings;
    }

    /**
     * Возвращает id прямого канала бот↔пользователь.
     * Создаёт канал, если не существует (запрос идемпотентен).
     * Возвращает empty если пользователь с таким email не найден в Mattermost.
     */
    public Optional<String> findDirectChannelId(String email) {
        String domain = adminSettings.get("mattermost.domain", "");
        String token  = adminSettings.get("mattermost.token", "");
        String botId  = adminSettings.get("mattermost.botId", "");

        HttpResponse<String> userResp = get(domain, token,
                "/api/v4/users/email/" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        if (userResp.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(userResp);
        String userId = extractId(userResp.body());

        // POST /api/v4/channels/direct — идемпотентно, возвращает существующий канал
        String body = "[\"" + botId + "\",\"" + userId + "\"]";
        HttpResponse<String> chanResp = post(domain, token, "/api/v4/channels/direct", body);
        requireSuccess(chanResp);

        return Optional.of(extractId(chanResp.body()));
    }

    /** Отправляет сообщение в канал. Бросает {@link MattermostException} при сбое. */
    public void sendMessage(String channelId, String text) {
        String domain = adminSettings.get("mattermost.domain", "");
        String token  = adminSettings.get("mattermost.token", "");

        String body = "{\"channel_id\":" + jsonString(channelId) + ",\"message\":" + jsonString(text) + "}";
        HttpResponse<String> resp = post(domain, token, "/api/v4/posts", body);
        requireSuccess(resp);
        log.debug("Сообщение отправлено в канал {}", channelId);
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
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
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

    static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    public static class MattermostException extends RuntimeException {
        public MattermostException(String message) { super(message); }
        public MattermostException(String message, Throwable cause) { super(message, cause); }
    }
}
