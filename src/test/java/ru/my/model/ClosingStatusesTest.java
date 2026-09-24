package ru.my.model;

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

        assertTrue(ClosingStatuses.isClosing(raw, "HELP", "10001"));
        assertFalse(ClosingStatuses.isClosing(raw, "HELP", "10002"));
    }

    /** Правила по категории статуса нет: проект без настройки ничего не шлёт. */
    @Test
    public void projectWithoutConfiguredStatusesIsNeverClosing() {
        assertFalse(ClosingStatuses.isClosing("HELP:10001", "SUP", "10002"));
        assertFalse(ClosingStatuses.isClosing("", "SUP", "10002"));
        assertFalse(ClosingStatuses.isClosing(null, "SUP", "10002"));
    }

    @Test
    public void parseKeepsInsertionOrder() {
        assertEquals(List.of("A", "B"), List.copyOf(ClosingStatuses.parse("A:1;B:2").keySet()));
    }
}
