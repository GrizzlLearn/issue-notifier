package ru.my.model;

import org.junit.Test;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ActionTemplatesTest {

    @Test
    public void buildsSettingKeys() {
        assertEquals("action.mention.enabled", ActionTemplates.enabledKey(NotificationAction.MENTION));
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
}
