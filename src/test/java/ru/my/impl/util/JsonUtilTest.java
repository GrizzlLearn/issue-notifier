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
        // U+000B вертикальная табуляция — не входит в \n \r \t
        assertEquals("\"\\u000b\"", JsonUtil.jsonString(""));
        assertEquals("\"\\u0001\"", JsonUtil.jsonString(""));
        assertEquals("\"\\u001f\"", JsonUtil.jsonString(""));
    }

    @Test
    public void passesEmojiThrough() {
        // emoji (U+1F680 = суррогатная пара в UTF-16) должен пройти как есть
        String rocket = "🚀"; // 🚀
        String result = JsonUtil.jsonString("Fix " + rocket + " deployment");
        assertEquals("\"Fix 🚀 deployment\"", result);
    }

    @Test
    public void escapesLoneSurrogate() {
        // одиночный high surrogate (без парного low) — невалидный UTF-16
        String lone = "\uD83D";
        assertEquals("\"\\ud83d\"", JsonUtil.jsonString(lone));
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
