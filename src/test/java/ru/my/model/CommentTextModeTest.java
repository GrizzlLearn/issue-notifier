package ru.my.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CommentTextModeTest {

    @Test
    public void resolvesSavedMode() {
        assertEquals(CommentTextMode.SHOWN, CommentTextMode.resolve("shown", "false"));
        assertEquals(CommentTextMode.HIDDEN, CommentTextMode.resolve("hidden", "false"));
        assertEquals(CommentTextMode.USER, CommentTextMode.resolve("user", "true"));
    }

    /**
     * Записи о режиме нет — поведение наследуется от старой галки, чтобы обновление
     * плагина не изменило настроенную инсталляцию.
     */
    @Test
    public void fallsBackToLegacyFlagWhenModeIsNotSaved() {
        assertEquals(CommentTextMode.HIDDEN, CommentTextMode.resolve("", "true"));
        assertEquals(CommentTextMode.USER, CommentTextMode.resolve("", "false"));
        assertEquals(CommentTextMode.USER, CommentTextMode.resolve(null, ""));
    }

    @Test
    public void unknownValueIsTreatedAsMissing() {
        assertEquals(CommentTextMode.HIDDEN, CommentTextMode.resolve("мусор", "true"));
        assertNull(CommentTextMode.byKey("мусор"));
    }
}
