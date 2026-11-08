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

    @Test
    public void readsOnlyRootIdAndDecodesEscapes() {
        assertEquals("root", MattermostClient.extractId(
                "{\"nested\":{\"id\":\"wrong\"},\"id\":\n\t\"r\\u006fot\"}"));
    }

    @Test
    public void rejectsInvalidIdsWithoutLeakingResponse() {
        for (String json : new String[]{null, "null", "[]", "{", "{\"id\":null}",
                "{\"id\":123}", "{\"id\":\" \"}", "{\"nested\":{\"id\":\"wrong\"}}"}) {
            try {
                MattermostClient.extractId(json);
                fail("Expected invalid id to be rejected");
            } catch (MattermostClient.MattermostException expected) {
                if (json != null) assertFalse(expected.getMessage().contains(json));
            }
        }
    }

}
