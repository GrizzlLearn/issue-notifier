package ru.my.impl.util;

import org.junit.Test;
import ru.my.impl.telegram.TelegramClient;
import ru.my.impl.mattermost.MattermostClient;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;
import static org.junit.Assert.*;

public class NotificationErrorsTest {
    @Test
    public void recordsSafeDeliveryContextAndTypedHttpStatus() {
        String result = NotificationErrors.summarize(NotificationErrors.delivery(NotificationAction.MENTION,
                NotificationChannel.TELEGRAM, "JIRAUSER123", new TelegramClient.TelegramException("Telegram API вернул статус 401")));
        assertTrue(result.contains("Упоминание"));
        assertTrue(result.contains("TELEGRAM"));
        assertTrue(result.contains("JIRAUSER123"));
        assertTrue(result.contains("Telegram API: HTTP 401"));
    }

    @Test
    public void neverCopiesArbitraryMessagesEvenFromNestedOrSuppressedFailures() {
        RuntimeException root = new IllegalStateException("secret-token private-body");
        root.addSuppressed(new TelegramClient.TelegramException("Telegram API вернул статус 401 secret-token"));
        root.addSuppressed(NotificationErrors.context("Отправка", new IllegalArgumentException("private-body")));
        String result = NotificationErrors.summarize(root);
        assertFalse(result.contains("secret-token"));
        assertFalse(result.contains("private-body"));
        assertFalse(result.contains("HTTP 401"));
    }

    @Test
    public void classifiesConfigurationAndNetworkWithoutEchoingPrivateValues() {
        assertEquals("Не указан Telegram Chat ID получателя", NotificationErrors.summarize(
                new IllegalStateException("У secret-name не указан Telegram Chat ID в настройках уведомлений")));
        assertEquals("Не задан токен Telegram-бота", NotificationErrors.summarize(
                new TelegramClient.TelegramException("Токен Telegram-бота не задан")));
        assertEquals("Не удалось подключиться к серверу", NotificationErrors.summarize(
                new MattermostClient.MattermostException("private url", new java.net.ConnectException("secret-token"))));
    }

    @Test
    public void limitsDetailsSizeAndHandlesExceptionCycles() {
        RuntimeException root = new RuntimeException("private-body");
        for (int i = 0; i < 20; i++) root.addSuppressed(NotificationErrors.context("x".repeat(500), new RuntimeException("secret")));
        root.getSuppressed()[0].addSuppressed(root);
        String result = NotificationErrors.summarize(root);
        assertTrue(result.length() <= 4000);
        assertTrue(result.split("\n").length <= 10);
        assertFalse(result.contains("secret"));
    }
}
