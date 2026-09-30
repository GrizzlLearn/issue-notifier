package ru.my.impl.mattermost;

import org.junit.Test;

import static org.junit.Assert.*;

public class MattermostClientTest {

    @Test
    public void extractIdFindsFirstIdField() {
        String json = "{\"id\":\"abc123\",\"name\":\"test\"}";
        assertEquals("abc123", MattermostClient.extractId(json));
    }

    @Test
    public void extractIdHandlesSpacesAroundColon() {
        String json = "{\"id\" : \"xyz789\"}";
        assertEquals("xyz789", MattermostClient.extractId(json));
    }

    @Test(expected = MattermostClient.MattermostException.class)
    public void extractIdThrowsWhenFieldMissing() {
        MattermostClient.extractId("{\"name\":\"test\"}");
    }

    /** Длинное сообщение обрезается под предел поста, иначе Mattermost отвечает 400 (С9). */
    @Test
    public void trimsMessageToPostLimit() {
        String trimmed = MattermostClient.trimToLimit("x".repeat(MattermostClient.MESSAGE_LIMIT + 500));

        assertTrue(trimmed.length() <= MattermostClient.MESSAGE_LIMIT);
        assertTrue(trimmed.endsWith("…"));
    }

    @Test
    public void shortMessageIsNotTouched() {
        assertEquals("коротко", MattermostClient.trimToLimit("коротко"));
    }

    /** Если обрезали внутри блока кода, забор закрывается — иначе остаток станет кодом. */
    @Test
    public void closesCodeFenceWhenCutInsideIt() {
        String text = "```diff\n" + "+ строка\n".repeat(3000);

        String trimmed = MattermostClient.trimToLimit(text);

        assertTrue(trimmed.endsWith("```"));
    }
}
