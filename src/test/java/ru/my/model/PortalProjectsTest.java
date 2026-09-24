package ru.my.model;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PortalProjectsTest {

    @Test
    public void parsesKeysKeepingOrder() {
        assertEquals(List.of("SUP", "DEV"), List.copyOf(PortalProjects.parse(" SUP , DEV ,")));
        assertEquals(Set.of(), PortalProjects.parse(null));
        assertEquals(Set.of(), PortalProjects.parse("  "));
    }

    @Test
    public void projectMatchesByOwnKey() {
        assertTrue(PortalProjects.contains("SUP,DEV", "", "SUP", null));
        assertFalse(PortalProjects.contains("SUP,DEV", "", "OTHER", null));
    }

    /** Категория заменяет перечисление проектов: сам проект в списке не нужен. */
    @Test
    public void projectMatchesByCategory() {
        assertTrue(PortalProjects.contains("", "10000,10010", "OTHER", 10010L));
        assertFalse(PortalProjects.contains("", "10000", "OTHER", 10010L));
    }

    @Test
    public void projectWithoutCategoryMatchesOnlyByKey() {
        assertFalse(PortalProjects.contains("", "10000", "OTHER", null));
        assertTrue(PortalProjects.contains("OTHER", "10000", "OTHER", null));
    }
}
