package ru.my.impl.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class JsonUtilTest {

    @Test
    public void escapesNewlineAndQuote() {
        assertEquals("\"hello\\nworld\"", JsonUtil.jsonString("hello\nworld"));
        assertEquals("\"say \\\"hi\\\"\"", JsonUtil.jsonString("say \"hi\""));
        assertEquals("\"a\\\\b\"", JsonUtil.jsonString("a\\b"));
    }

    @Test
    public void escapesCarriageReturnAndTab() {
        assertEquals("\"a\\rb\"", JsonUtil.jsonString("a\rb"));
        assertEquals("\"a\\tb\"", JsonUtil.jsonString("a\tb"));
    }

    @Test
    public void escapesControlCharsBelowU0020() {
        // U+000B вертикальная табуляция — не входит в \n \r \t, должна стать
        assertEquals("\"\\u000b\"", JsonUtil.jsonString(""));
        // U+0001
        assertEquals("\"\\u0001\"", JsonUtil.jsonString(""));
        // U+001F
        assertEquals("\"\\u001f\"", JsonUtil.jsonString(""));
    }

    @Test
    public void handlesNull() {
        assertEquals("null", JsonUtil.jsonString(null));
    }

    @Test
    public void handlesEmptyString() {
        assertEquals("\"\"", JsonUtil.jsonString(""));
    }

    @Test
    public void handlesPlainAscii() {
        assertEquals("\"hello world\"", JsonUtil.jsonString("hello world"));
    }
}
