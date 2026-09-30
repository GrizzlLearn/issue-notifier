package ru.my.impl.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TextLimitTest {

    @Test
    public void shortTextIsNotTouched() {
        assertEquals("коротко", TextLimit.cut("коротко", 100));
        assertNull(TextLimit.cut(null, 10));
    }

    @Test
    public void cutsToLimit() {
        assertEquals("абвг", TextLimit.cut("абвгде", 4));
    }

    /** Эмодзи — суррогатная пара: половина ломает JSON и приезжает знаком замены. */
    @Test
    public void doesNotSplitSurrogatePair() {
        String text = "аб😀вг";          // 😀 занимает два char

        String cut = TextLimit.cut(text, 3);  // граница попадает внутрь пары

        assertEquals("аб", cut);
        assertTrue(cut.codePoints().allMatch(Character::isDefined));
    }

    @Test
    public void keepsWholePairWhenItFits() {
        assertEquals("аб😀", TextLimit.cut("аб😀вг", 4));
    }
}
