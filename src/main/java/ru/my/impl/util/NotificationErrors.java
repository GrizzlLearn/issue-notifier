package ru.my.impl.util;

import ru.my.impl.telegram.TelegramClient;
import ru.my.impl.mattermost.MattermostClient;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Безопасная диагностика: произвольные сообщения исключений не сохраняются и не показываются. */
public final class NotificationErrors {
    private static final Pattern STATUS = Pattern.compile("(?:Telegram API|Mattermost API|getUpdates) вернул статус ([1-5][0-9]{2})");
    private NotificationErrors() { }

    /** Добавляет только действие, канал и нормализованный ключ Jira к причине сбоя. */
    public static RuntimeException delivery(NotificationAction action, NotificationChannel channel,
                                            String userKey, Throwable cause) {
        return context((action == null ? "Изменение полей" : action.title()) + "; канал " + channel.name()
                + "; получатель " + safe(userKey, 100), cause);
    }

    /** trustedContext должен состоять из названий шагов и идентификаторов, без содержимого задач. */
    public static RuntimeException context(String trustedContext, Throwable cause) {
        return new Context(safe(trustedContext, 300), cause);
    }

    /** Не больше десяти деталей и 4000 UTF-16 символов, включая причины и suppressed. */
    public static String summarize(Throwable failure) {
        List<String> details = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(failure, "", details, visited);
        return TextLimit.cut(String.join("\n", details), 4000);
    }

    private static void collect(Throwable error, String prefix, List<String> details, Set<Throwable> visited) {
        if (error == null || details.size() >= 10 || visited.size() >= 100 || !visited.add(error)) return;
        String context = prefix;
        if (error instanceof Context) {
            context = safe(prefix + error.getMessage() + ": ", 350);
        } else if (error.getCause() == null && error.getSuppressed().length == 0) {
            details.add(context + reason(error));
        }
        collect(error.getCause(), context, details, visited);
        for (Throwable suppressed : error.getSuppressed()) collect(suppressed, context, details, visited);
    }

    private static String reason(Throwable error) {
        String message = error.getMessage();
        if (error instanceof TelegramClient.TelegramException || error instanceof MattermostClient.MattermostException) {
            var status = STATUS.matcher(message == null ? "" : message);
            if (status.matches()) {
                String channel = error instanceof TelegramClient.TelegramException ? "Telegram" : "Mattermost";
                return channel + " API: HTTP " + status.group(1);
            }
            if ("Токен Telegram-бота не задан".equals(message)) return "Не задан токен Telegram-бота";
            if (message != null && message.startsWith("Токен Telegram-бота содержит недопустимые символы")) return "Недопустимый токен Telegram-бота";
            if ("Пользователь с таким email не найден в Mattermost".equals(message)) return "Получатель не найден в Mattermost";
            if (message != null && message.matches("Ошибка HTTP-запроса \\(Http(?:Connect)?TimeoutException\\): .*")) return "Истекло время ожидания сети";
            if (message != null && message.matches("Ошибка HTTP-запроса \\(ConnectException\\): .*")) return "Не удалось подключиться к серверу";
            if (message != null && message.matches("Ошибка HTTP-запроса \\(UnknownHostException\\): .*")) return "Не удалось определить адрес сервера";
            if (message != null && message.matches("Ошибка HTTP-запроса \\(SSL(?:Handshake|Protocol|PeerUnverified)?Exception\\): .*")) return "Ошибка защищённого соединения";
            if ("HTTP-запрос прерван".equals(message)) return "Отправка прервана";
        }
        if (error instanceof HttpTimeoutException || error instanceof SocketTimeoutException) return "Истекло время ожидания сети";
        if (error instanceof ConnectException) return "Не удалось подключиться к серверу";
        if (error instanceof UnknownHostException) return "Не удалось определить адрес сервера";
        if (error instanceof SSLException) return "Ошибка защищённого соединения";
        if (error instanceof InterruptedException) return "Отправка прервана";
        if (error instanceof IllegalStateException && message != null) {
            if (message.matches("У .* не указан Telegram Chat ID в настройках уведомлений")) return "Не указан Telegram Chat ID получателя";
            if (message.matches("У пользователя .* не указан email в Jira")) return "Не указан email получателя в Jira";
            if (message.matches("Пользователь .* не найден в Mattermost по своему email")) return "Получатель не найден в Mattermost";
            if (message.startsWith("В Jira не настроен исходящий SMTP-сервер")) return "Не настроен исходящий SMTP-сервер Jira";
            if (message.startsWith("Исходящая почта Jira отключена")) return "Исходящая почта Jira отключена";
        }
        return "Ошибка обработки (" + safe(error.getClass().getSimpleName(), 80) + ")";
    }

    private static String safe(String text, int limit) {
        return TextLimit.cut(text == null ? "?" : text.replaceAll("[\\p{Cntrl}]", " "), limit);
    }

    private static final class Context extends RuntimeException {
        private Context(String message, Throwable cause) { super(message, cause); }
    }
}
