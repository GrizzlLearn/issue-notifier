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

    @Test
    public void jsonStringEscapesSpecialChars() {
        assertEquals("\"hello\\nworld\"", MattermostClient.jsonString("hello\nworld"));
        assertEquals("\"say \\\"hi\\\"\"", MattermostClient.jsonString("say \"hi\""));
        assertEquals("\"a\\\\b\"", MattermostClient.jsonString("a\\b"));
    }

    @Test
    public void jsonStringHandlesNull() {
        assertEquals("null", MattermostClient.jsonString(null));
    }
}
