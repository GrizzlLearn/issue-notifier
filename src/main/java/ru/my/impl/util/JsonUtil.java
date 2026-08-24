package ru.my.impl.util;

/**
 * Утилита для ручной сериализации строк в JSON без внешних зависимостей.
 * Экранирует все управляющие символы U+0000–U+001F согласно RFC 8259,
 * а также одиночные суррогаты (невалидный UTF-16).
 * Символы вне BMP (emoji, U+10000+) передаются как есть — они корректны в UTF-8.
 */
public final class JsonUtil {

    private JsonUtil() {}

    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                // символ вне BMP (например, emoji) — валиден в UTF-8, передаём как есть
                sb.appendCodePoint(cp);
            } else {
                char c = (char) cp;
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default   -> {
                        if (c < 0x20 || Character.isSurrogate(c)) {
                            // управляющие символы и одиночные суррогаты → unicode escape
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
