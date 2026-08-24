package ru.my.impl.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TextDiffTest {

    @Test
    public void emptyInputsProduceEmptyDiff() {
        assertTrue(TextDiff.diff(null, null).isEmpty());
        assertTrue(TextDiff.diff("", "").isEmpty());
        assertTrue(TextDiff.diff(null, "").isEmpty());
    }

    @Test
    public void identicalTextProducesOnlyContextLines() {
        List<TextDiff.Line> lines = TextDiff.diff("a\nb\nc", "a\nb\nc");

        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.marker() == ' '));
    }

    @Test
    public void additionFromEmptyProducesAllPlusLines() {
        List<TextDiff.Line> lines = TextDiff.diff(null, "x\ny");

        assertEquals(2, lines.size());
        assertEquals('+', lines.get(0).marker());
        assertEquals('+', lines.get(1).marker());
        assertEquals("x", lines.get(0).text());
        assertEquals("y", lines.get(1).text());
    }

    @Test
    public void removalToEmptyProducesAllMinusLines() {
        List<TextDiff.Line> lines = TextDiff.diff("x\ny", null);

        assertEquals(2, lines.size());
        assertEquals('-', lines.get(0).marker());
        assertEquals('-', lines.get(1).marker());
    }

    @Test
    public void singleLineChangeProducesMinusThenPlus() {
        List<TextDiff.Line> lines = TextDiff.diff("old", "new");

        assertEquals(2, lines.size());
        assertEquals('-', lines.get(0).marker());
        assertEquals("old", lines.get(0).text());
        assertEquals('+', lines.get(1).marker());
        assertEquals("new", lines.get(1).text());
    }

    @Test
    public void changeInMiddlePreservesContextLines() {
        List<TextDiff.Line> lines = TextDiff.diff("a\nb\nc", "a\nB\nc");

        assertEquals(4, lines.size());
        assertEquals(' ', lines.get(0).marker()); // a — контекст
        assertEquals('-', lines.get(1).marker()); // b — удалено
        assertEquals('+', lines.get(2).marker()); // B — добавлено
        assertEquals(' ', lines.get(3).marker()); // c — контекст
    }

    @Test
    public void insertedLineInMiddle() {
        List<TextDiff.Line> lines = TextDiff.diff("a\nc", "a\nb\nc");

        assertEquals(3, lines.size());
        assertEquals(' ', lines.get(0).marker()); // a
        assertEquals('+', lines.get(1).marker()); // b добавлено
        assertEquals(' ', lines.get(2).marker()); // c
    }

    @Test
    public void deletedLineInMiddle() {
        List<TextDiff.Line> lines = TextDiff.diff("a\nb\nc", "a\nc");

        assertEquals(3, lines.size());
        assertEquals(' ', lines.get(0).marker()); // a
        assertEquals('-', lines.get(1).marker()); // b удалено
        assertEquals(' ', lines.get(2).marker()); // c
    }
}
