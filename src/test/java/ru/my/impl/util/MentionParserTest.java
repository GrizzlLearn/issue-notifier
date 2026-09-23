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
}
