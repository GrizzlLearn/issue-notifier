package ru.my.impl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт упоминания пользователей из текста Jira.
 * <p>
 * В wiki-разметке Jira Server/DC упоминание хранится как {@code [~username]} —
 * именно в таком виде редактор сохраняет то, что пользователь набрал через «@».
 */
public final class MentionParser {

    private static final Pattern MENTION = Pattern.compile("\\[~([^\\]\\s]+)]");

    private MentionParser() {
    }

    /**
     * @param text текст комментария или описания; может быть null
     * @return имена упомянутых пользователей в порядке появления, без повторов
     */
    public static List<String> parse(String text) {
        List<String> names = new ArrayList<>();
        if (text == null) {
            return names;
        }
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
