package ru.my.model;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ActionTemplatesTest {

    @Test
    public void buildsSettingKeys() {
        assertEquals("action.closed.template.telegram",
                ActionTemplates.templateKey(NotificationAction.CLOSED, NotificationChannel.TELEGRAM));
        assertEquals(NotificationAction.CLOSED,
                ActionTemplates.actionOfTemplateKey("action.closed.template.telegram"));
        assertNull(ActionTemplates.actionOfTemplateKey("mattermost.domain"));
    }

    @Test
    public void substitutesPlaceholders() {
        String result = ActionTemplates.render("{issueKey} — {summary}",
                Map.of("issueKey", "SUP-1", "summary", "Не работает портал"),
                NotificationChannel.MATTERMOST);

        assertEquals("SUP-1 — Не работает портал", result);
    }

    /** В Telegram сообщение уходит с parse_mode=HTML, поэтому значения экранируются. */
    @Test
    public void escapesValuesForTelegramButNotForMattermost() {
        Map<String, String> values = Map.of("summary", "a < b & c");

        assertEquals("<b>a &lt; b &amp; c</b>",
                ActionTemplates.render("<b>{summary}</b>", values, NotificationChannel.TELEGRAM));
        assertEquals("**a < b & c**",
                ActionTemplates.render("**{summary}**", values, NotificationChannel.MATTERMOST));
    }

    @Test
    public void missingValueRendersAsEmptyString() {
        assertEquals("ключ: ", ActionTemplates.render("ключ: {issueKey}",
                java.util.Collections.singletonMap("issueKey", null), NotificationChannel.TELEGRAM));
    }

    @Test
    public void findsUnknownPlaceholders() {
        assertEquals(List.of("issuekey", "foo"),
                ActionTemplates.unknownPlaceholders("{issuekey} {summary} {foo}", NotificationAction.MENTION));
        assertTrue(ActionTemplates.unknownPlaceholders("{issueKey} {comment}", NotificationAction.MENTION).isEmpty());
    }

    /**
     * Значение, внутри которого встретился текст вида {@code {comment}}, не должно
     * стать плейсхолдером на следующей итерации подстановки: иначе заголовок задачи
     * подменялся бы текстом комментария (С4).
     */
    @Test
    public void valueThatLooksLikePlaceholderIsNotSubstituted() {
        String rendered = ActionTemplates.render("{summary} / {comment}",
                Map.of("summary", "правка {comment}", "comment", "секретный текст"),
                NotificationChannel.MATTERMOST);

        assertEquals("правка {comment} / секретный текст", rendered);
    }

    /** Три обратные кавычки в значении не должны закрывать блок кода Mattermost (С17). */
    @Test
    public void codeFenceInValueIsNeutralizedForMattermost() {
        String rendered = ActionTemplates.render("{comment}",
                Map.of("comment", "```\n![x](https://evil.example.com/x.png)"),
                NotificationChannel.MATTERMOST);

        assertFalse(rendered.contains("```"));
        assertTrue(rendered.contains("evil.example.com"));   // сам текст остаётся читаемым
    }

    /**
     * Серия любой длины: четыре и пять кавычек тоже закрывают блок кода,
     * а простая замена «три на три с разделителем» оставляла три подряд в хвосте.
     */
    @Test
    public void longerBacktickRunsAreAlsoNeutralized() {
        for (int count = 3; count <= 8; count++) {
            String value = "`".repeat(count);
            assertFalse("серия из " + count + " кавычек осталась забором",
                    ActionTemplates.breakCodeFence(value).contains("```"));
        }
    }
}
