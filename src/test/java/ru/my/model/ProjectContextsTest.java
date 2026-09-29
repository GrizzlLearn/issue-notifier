package ru.my.model;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectContextsTest {

    private static ProjectContext context(String id, Set<String> projects, Set<String> categories,
                                          Map<String, ProjectContext.ActionSetting> actions) {
        return new ProjectContext(id, "Контекст " + id, projects, categories, actions, Map.of());
    }

    private static Map<String, ProjectContext.ActionSetting> mentionOnly() {
        return Map.of(NotificationAction.MENTION.key(), new ProjectContext.ActionSetting(true, ""));
    }

    @Test
    public void parseReturnsDefaultContextWhenSettingIsEmpty() {
        List<ProjectContext> contexts = ProjectContexts.parse("");

        assertEquals(1, contexts.size());
        assertTrue(contexts.get(0).isDefault());
    }

    @Test
    public void parseAppendsDefaultContextLast() {
        String raw = ProjectContexts.format(List.of(context("a1", Set.of("HELP"), Set.of(), Map.of())));

        List<ProjectContext> contexts = ProjectContexts.parse(raw);

        assertEquals(2, contexts.size());
        assertEquals("a1", contexts.get(0).id());
        assertTrue(contexts.get(1).isDefault());
    }

    /**
     * Название контекста админ пишет руками, поэтому в нём легко окажутся кавычки,
     * запятые и двоеточия — разделители старых настроек плагина. JSON их выдерживает.
     */
    @Test
    public void roundTripKeepsNameWithJsonSpecialCharacters() {
        String name = "Категория \"А\": поддержка, 1-я линия; \\ конец";
        ProjectContext saved = new ProjectContext("a1", name, Set.of("HELP"), Set.of("10000"),
                mentionOnly(), Map.of("HELP", Set.of("10001", "3")));

        List<ProjectContext> contexts = ProjectContexts.parse(ProjectContexts.format(List.of(saved)));

        ProjectContext loaded = contexts.get(0);
        assertEquals(name, loaded.name());
        assertEquals(Set.of("HELP"), loaded.projects());
        assertEquals(Set.of("10000"), loaded.categories());
        assertTrue(loaded.isEnabled(NotificationAction.MENTION));
        assertEquals(Set.of("10001", "3"), loaded.closingStatuses("HELP"));
    }

    @Test
    public void roundTripKeepsActionRecipients() {
        ProjectContext saved = context("a1", Set.of("HELP"), Set.of(),
                Map.of(NotificationAction.CLOSED.key(),
                        new ProjectContext.ActionSetting(true, "reporter,assignee")));

        ProjectContext loaded = ProjectContexts.parse(ProjectContexts.format(List.of(saved))).get(0);

        assertEquals("reporter,assignee", loaded.recipients(NotificationAction.CLOSED));
    }

    @Test
    public void parseIgnoresGarbage() {
        assertEquals(1, ProjectContexts.parse("не json").size());
        assertEquals(1, ProjectContexts.parse("{\"id\":\"a1\"}").size());
        assertEquals(1, ProjectContexts.parse("[\"строка\", 42, null]").size());
    }

    /** Контекст без id нельзя ни настроить, ни удалить — он отбрасывается. */
    @Test
    public void parseDropsContextWithoutId() {
        List<ProjectContext> contexts = ProjectContexts.parse("[{\"name\":\"Без id\"}]");

        assertEquals(1, contexts.size());
        assertTrue(contexts.get(0).isDefault());
    }

    /** Опечатка в ключе действия не должна жить в настройках и путать при отладке. */
    @Test
    public void parseDropsUnknownActionKey() {
        String raw = "[{\"id\":\"a1\",\"actions\":{\"mentoin\":{\"enabled\":true}}}]";

        ProjectContext loaded = ProjectContexts.parse(raw).get(0);

        assertTrue(loaded.actionKeys().isEmpty());
    }

    @Test
    public void actionIsDisabledWhenThereIsNoRecord() {
        ProjectContext loaded = ProjectContexts.parse("[{\"id\":\"a1\"}]").get(0);

        assertFalse(loaded.isEnabled(NotificationAction.MENTION));
        assertEquals("", loaded.recipients(NotificationAction.MENTION));
    }

    @Test
    public void resolveReturnsContextOfExplicitProject() {
        List<ProjectContext> contexts = List.of(
                context("a1", Set.of("HELP"), Set.of(), Map.of()),
                ProjectContexts.emptyDefault());

        assertEquals("a1", ProjectContexts.resolve(contexts, "HELP", null).id());
    }

    @Test
    public void resolveReturnsContextOfProjectCategory() {
        List<ProjectContext> contexts = List.of(
                context("a1", Set.of(), Set.of("10000"), Map.of()),
                ProjectContexts.emptyDefault());

        assertEquals("a1", ProjectContexts.resolve(contexts, "HELP", 10000L).id());
    }

    /**
     * Исключение «категория целиком, кроме одного проекта»: проект, вынесенный
     * в свой контекст, должен работать по нему, а не по контексту своей категории.
     */
    @Test
    public void resolvePrefersExplicitProjectOverCategory() {
        List<ProjectContext> contexts = List.of(
                context("byCategory", Set.of(), Set.of("10000"), Map.of()),
                context("byProject", Set.of("HELP"), Set.of(), Map.of()),
                ProjectContexts.emptyDefault());

        assertEquals("byProject", ProjectContexts.resolve(contexts, "HELP", 10000L).id());
    }

    /** Уникальность проверяется при сохранении; гонка двух админов её обходит. */
    @Test
    public void resolveTakesFirstContextWhenProjectIsDuplicated() {
        List<ProjectContext> contexts = List.of(
                context("first", Set.of("HELP"), Set.of(), Map.of()),
                context("second", Set.of("HELP"), Set.of(), Map.of()),
                ProjectContexts.emptyDefault());

        assertEquals("first", ProjectContexts.resolve(contexts, "HELP", 10000L).id());
    }

    @Test
    public void resolveFallsBackToDefaultContext() {
        List<ProjectContext> contexts = ProjectContexts.parse(
                ProjectContexts.format(List.of(context("a1", Set.of("HELP"), Set.of(), mentionOnly()))));

        ProjectContext resolved = ProjectContexts.resolve(contexts, "OTHER", 99999L);

        assertTrue(resolved.isDefault());
        assertFalse(resolved.isEnabled(NotificationAction.MENTION));
    }

    /** Категория, удалённая в Jira, остаётся в настройке — резолв не должен падать. */
    @Test
    public void resolveHandlesMissingCategory() {
        List<ProjectContext> contexts = List.of(
                context("a1", Set.of(), Set.of("10000"), Map.of()),
                ProjectContexts.emptyDefault());

        assertTrue(ProjectContexts.resolve(contexts, "HELP", null).isDefault());
    }

    @Test
    public void closingStatusesAreKeptPerProject() {
        ProjectContext saved = new ProjectContext("a1", "Портал", Set.of("HELP", "SUP"), Set.of(),
                Map.of(), Map.of("HELP", Set.of("10001")));

        ProjectContext loaded = ProjectContexts.parse(ProjectContexts.format(List.of(saved))).get(0);

        assertTrue(loaded.isClosing("HELP", "10001"));
        assertFalse(loaded.isClosing("HELP", "3"));
        assertFalse("проект без выбранных статусов о закрытии не уведомляет",
                loaded.isClosing("SUP", "10001"));
    }

    @Test
    public void byIdFindsContextAndReturnsNullForUnknown() {
        List<ProjectContext> contexts = ProjectContexts.parse(
                ProjectContexts.format(List.of(context("a1", Set.of(), Set.of(), Map.of()))));

        ProjectContext found = ProjectContexts.byId(contexts, "a1");
        ProjectContext builtIn = ProjectContexts.byId(contexts, ProjectContexts.DEFAULT_ID);

        assertNotNull(found);
        assertEquals("a1", found.id());
        assertNotNull(builtIn);
        assertEquals(ProjectContexts.DEFAULT_ID, builtIn.id());
        assertNull(ProjectContexts.byId(contexts, "нет такого"));
    }

    /** Сохранённый default не должен продублироваться добавленным при разборе. */
    @Test
    public void parseKeepsSingleDefaultContext() {
        String raw = ProjectContexts.format(List.of(
                context("a1", Set.of("HELP"), Set.of(), Map.of()),
                new ProjectContext(ProjectContexts.DEFAULT_ID, "Остальные проекты",
                        Set.of(), Set.of(), mentionOnly(), Map.of())));

        List<ProjectContext> contexts = ProjectContexts.parse(raw);

        assertEquals(2, contexts.size());
        assertTrue(contexts.get(1).isDefault());
        assertTrue("настройки встроенного контекста должны сохраняться",
                contexts.get(1).isEnabled(NotificationAction.MENTION));
    }
}
