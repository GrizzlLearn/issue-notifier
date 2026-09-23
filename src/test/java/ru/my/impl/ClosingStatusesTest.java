package ru.my.impl;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ClosingStatusesTest {

    @Test
    public void parsesProjectsAndStatuses() {
        Map<String, Set<String>> parsed = ClosingStatuses.parse("HELP:10001,3;SUP:10002");

        assertEquals(Set.of("10001", "3"), parsed.get("HELP"));
        assertEquals(Set.of("10002"), parsed.get("SUP"));
    }

    @Test
    public void skipsMalformedChunks() {
        Map<String, Set<String>> parsed = ClosingStatuses.parse("HELP:10001;;мусор;:5;SUP:");

        assertEquals(1, parsed.size());
        assertEquals(Set.of("10001"), parsed.get("HELP"));
        assertTrue(ClosingStatuses.parse(null).isEmpty());
        assertTrue(ClosingStatuses.parse("  ").isEmpty());
    }

    @Test
    public void usesConfiguredStatusesForProject() {
        String raw = "HELP:10001,3";

        assertTrue(ClosingStatuses.isClosing(raw, "HELP", "10001", false));
        // статус категории «Done», но для проекта выбран другой — не закрывающий
        assertFalse(ClosingStatuses.isClosing(raw, "HELP", "10002", true));
    }

    /** Для проектов без своей настройки работает запасное правило по категории статуса. */
    @Test
    public void fallsBackToStatusCategoryWhenProjectNotConfigured() {
        String raw = "HELP:10001";

        assertTrue(ClosingStatuses.isClosing(raw, "SUP", "10002", true));
        assertFalse(ClosingStatuses.isClosing(raw, "SUP", "3", false));
        assertTrue(ClosingStatuses.isClosing("", "SUP", "10002", true));
    }

    @Test
    public void parseKeepsInsertionOrder() {
        assertEquals(List.of("A", "B"), List.copyOf(ClosingStatuses.parse("A:1;B:2").keySet()));
    }
}
