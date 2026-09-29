package ru.my.impl.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MentionParserTest {

    @Test
    public void parsesSingleMention() {
        assertEquals(List.of("ipetrov"), MentionParser.parse("[~ipetrov] посмотри"));
    }

    @Test
    public void parsesSeveralMentionsWithoutDuplicates() {
        assertEquals(List.of("ipetrov", "asidorov"),
                MentionParser.parse("[~ipetrov] и [~asidorov], а также снова [~ipetrov]"));
    }

    @Test
    public void returnsEmptyListForTextWithoutMentions() {
        assertTrue(MentionParser.parse("обычный комментарий про [ссылку]").isEmpty());
        assertTrue(MentionParser.parse(null).isEmpty());
    }

    /** Сотня упоминаний в одном комментарии — это рассылка, а не уведомление. */
    @Test
    public void stopsAfterLimit() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < MentionParser.MAX_MENTIONS + 5; i++) {
            text.append("[~user").append(i).append("] ");
        }

        assertEquals(MentionParser.MAX_MENTIONS, MentionParser.parse(text.toString()).size());
    }
}
