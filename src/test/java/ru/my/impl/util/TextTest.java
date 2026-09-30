package ru.my.impl.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TextTest {

    @Test
    public void shortTextIsNotTouched() {
        assertEquals("коротко", Text.cut("коротко", 100));
        assertEquals(null, Text.cut(null, 10));
    }

    @Test
    public void cutsToLimit() {
        assertEquals("абвг", Text.cut("абвгде", 4));
    }

    /** Эмодзи — суррогатная пара: половина ломает JSON и приезжает знаком замены. */
    @Test
    public void doesNotSplitSurrogatePair() {
        String text = "аб😀вг";          // 😀 занимает два char

        String cut = Text.cut(text, 3);  // граница попадает внутрь пары

        assertEquals("аб", cut);
        assertTrue(cut.codePoints().allMatch(Character::isDefined));
    }

    @Test
    public void keepsWholePairWhenItFits() {
        assertEquals("аб😀", Text.cut("аб😀вг", 4));
    }
}
