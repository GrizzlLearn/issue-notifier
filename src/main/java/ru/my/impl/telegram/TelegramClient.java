package ru.my.impl.telegram;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-клиент для Telegram Bot API.
 * Токен бота читается из {@link AdminSettingsService} при каждом вызове —
 * изменения вступают в силу без перезапуска плагина.
 */
@Named
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String API_BASE = "https://api.telegram.org/bot";

    /** Лимит sendMessage у Bot API; более длинное сообщение отбивается с 400. */
    static final int MESSAGE_LIMIT = 4096;
    private static final String ELLIPSIS = "\n…";

    /** Теги, которые ставит форматтер: {@code <b>}, {@code <s>}, {@code <pre>}. */
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]+)[^>]*>");

    private final AdminSettingsService adminSettings;
    private final HttpClient http;
    private final ExecutorService executor;

    @Inject
    public TelegramClient(AdminSettingsService adminSettings) {
        this.adminSettings = adminSettings;
        // свой executor, чтобы погасить потоки при выгрузке бандла: в Java 17
        // у HttpClient нет close(), и его собственный пул держал бы
        // classloader старого плагина после каждого обновления
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "telegram-http");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(executor)
                .build();
    }

    /**
     * Возвращает новые обновления начиная с {@code offset}.
     * Если токен не задан — возвращает пустой массив без HTTP-вызова.
     */
    public String getUpdates(long offset) {
        String token = adminSettings.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "");
        if (token.isBlank()) return "{\"ok\":true,\"result\":[]}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + token + "/getUpdates?offset=" + offset))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = execute(req, token);
        if (resp.statusCode() != 200) {
            throw new TelegramException("getUpdates вернул статус " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** Отправляет HTML-сообщение в указанный чат. Бросает {@link TelegramException} при сбое. */
    public void sendMessage(String chatId, String htmlText) {
        String token = adminSettings.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "");
        if (token.isBlank()) {
            throw new TelegramException("Токен Telegram-бота не задан");
        }

        String body = "{\"chat_id\":" + JsonUtil.jsonString(chatId)
                + ",\"text\":" + JsonUtil.jsonString(trimToLimit(htmlText))
                + ",\"parse_mode\":\"HTML\"}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + token + "/sendMessage"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = execute(req, token);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new TelegramException("Telegram API вернул статус " + resp.statusCode() + ": " + resp.body());
        }
        log.debug("Сообщение отправлено в чат {}", chatId);
    }

    /**
     * Обрезает сообщение под лимит Telegram. Обрезать «по живому» нельзя:
     * {@code parse_mode=HTML} требует закрытых тегов, и недописанный
     * {@code <b>} или {@code <pre>} вернул бы 400 вместо доставки — поэтому
     * оборванный тег отбрасывается, а незакрытые закрываются в обратном порядке.
     */
    static String trimToLimit(String html) {
        if (html == null || html.length() <= MESSAGE_LIMIT) {
            return html;
        }
        String cut = html.substring(0, MESSAGE_LIMIT - ELLIPSIS.length());
        int lastOpen = cut.lastIndexOf('<');
        if (lastOpen > cut.lastIndexOf('>')) {
            cut = cut.substring(0, lastOpen);
        }
        StringBuilder sb = new StringBuilder(cut).append(ELLIPSIS);
        for (String tag : openTags(cut)) {
            sb.append("</").append(tag).append('>');
        }
        return sb.toString();
    }

    /** Незакрытые теги обрезанного текста, в порядке закрытия — сначала внутренний. */
    private static Deque<String> openTags(String html) {
        Deque<String> stack = new ArrayDeque<>();
        Matcher m = TAG.matcher(html);
        while (m.find()) {
            String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (m.group(1).isEmpty()) {
                stack.push(name);
            } else if (name.equals(stack.peek())) {
                stack.pop();
            }
        }
        return stack;
    }

    /** ponytail: HttpClient в Java 17 не закрывается — гасим хотя бы его пул потоков. */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    private HttpResponse<String> execute(HttpRequest req, String token) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TelegramException("Ошибка HTTP-запроса: " + mask(e.getMessage(), token), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("HTTP-запрос прерван", e);
        }
    }

    /**
     * Bot API требует токен прямо в пути URL, а сообщение сетевой ошибки часто
     * содержит этот URL — и уезжает в plugin.log. Вырезаем токен из текста.
     */
    static String mask(String message, String token) {
        if (message == null) {
            return "";
        }
        return token.isBlank() ? message : message.replace(token, "***");
    }

    public static class TelegramException extends RuntimeException {
        public TelegramException(String message) { super(message); }
        public TelegramException(String message, Throwable cause) { super(message, cause); }
    }
}
