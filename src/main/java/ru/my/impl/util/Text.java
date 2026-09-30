package ru.my.impl.util;

/**
 * Обрезка текста для каналов доставки.
 * <p>
 * Отдельный класс, потому что обрезают в четырёх местах — текст комментария,
 * ячейка письма, пост Mattermost и сообщение Telegram, — и все они режут по
 * индексу UTF-16, где граница может попасть в середину суррогатной пары.
 */
public final class Text {

    private Text() {
    }

    /**
     * Обрезает строку до {@code limit} символов, не разрывая суррогатную пару:
     * половина эмодзи ломает JSON-кодирование и приезжает получателю символом
     * замены, а Mattermost на такой пост может ответить 400.
     *
     * @return исходная строка, если она короче предела; иначе обрезанная — она
     *         может быть на один символ короче {@code limit}
     */
    public static String cut(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
