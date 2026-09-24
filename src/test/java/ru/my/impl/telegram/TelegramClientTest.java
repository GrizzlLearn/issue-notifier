package ru.my.impl.telegram;

import org.junit.Test;

import static org.junit.Assert.*;

public class TelegramClientTest {

    @Test
    public void keepsShortMessageAsIs() {
        String html = "<b>PROJ-1</b> — привет";
        assertSame(html, TelegramClient.trimToLimit(html));
    }

    @Test
    public void trimsToLimit() {
        String html = "x".repeat(TelegramClient.MESSAGE_LIMIT * 2);
        assertTrue(TelegramClient.trimToLimit(html).length() <= TelegramClient.MESSAGE_LIMIT);
    }

    /**
     * Обрезка «по живому» оставила бы открытый {@code <pre>}, и Telegram вернул бы
     * 400 вместо доставки: длинный diff должен доезжать с закрытыми тегами.
     */
    @Test
    public void closesTagsLeftOpenByTrimming() {
        String html = "<b>PROJ-1</b>\n<pre>" + "строка\n".repeat(TelegramClient.MESSAGE_LIMIT);

        String trimmed = TelegramClient.trimToLimit(html);

        assertTrue(trimmed.endsWith("</pre>"));
        assertEquals(countOf(trimmed, "<pre>"), countOf(trimmed, "</pre>"));
    }

    /** Оборванный на середине тег отбрасывается, а не уезжает как {@code "<b"}. */
    @Test
    public void dropsDanglingTag() {
        String html = "y".repeat(TelegramClient.MESSAGE_LIMIT - 3) + "<b>текст</b>";

        String trimmed = TelegramClient.trimToLimit(html);

        assertFalse(trimmed.contains("<b"));
    }

    /** Токен лежит в URL по требованию Bot API — в текст ошибки он попасть не должен. */
    @Test
    public void masksTokenInErrorText() {
        String token = "123456:AAH-secret";
        String message = "failed to connect to https://api.telegram.org/bot" + token + "/getUpdates";

        String masked = TelegramClient.mask(message, token);

        assertFalse(masked.contains(token));
        assertTrue(masked.contains("***"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
