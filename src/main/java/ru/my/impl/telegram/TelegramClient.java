package ru.my.impl.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.impl.ChannelKeys;
import ru.my.impl.util.JsonUtil;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

    private final AdminSettingsService adminSettings;
    private final HttpClient http;

    @Inject
    public TelegramClient(AdminSettingsService adminSettings) {
        this.adminSettings = adminSettings;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
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
        HttpResponse<String> resp = execute(req);
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
                + ",\"text\":" + JsonUtil.jsonString(htmlText)
                + ",\"parse_mode\":\"HTML\"}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + token + "/sendMessage"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = execute(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new TelegramException("Telegram API вернул статус " + resp.statusCode() + ": " + resp.body());
        }
        log.debug("Сообщение отправлено в чат {}", chatId);
    }

    private HttpResponse<String> execute(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TelegramException("Ошибка HTTP-запроса: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("HTTP-запрос прерван", e);
        }
    }

    public static class TelegramException extends RuntimeException {
        public TelegramException(String message) { super(message); }
        public TelegramException(String message, Throwable cause) { super(message, cause); }
    }
}
