package ru.my.servlet;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.project.MockProject;
import com.atlassian.jira.project.Project;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AdminPageDataTest {

    private static Project project(long id, String key, String name, String typeKey) {
        MockProject p = new MockProject(id, key, name);
        p.setProjectTypeKey(typeKey);
        return p;
    }

    private static Status status(String id, String name, String categoryKey) {
        StatusCategory category = mock(StatusCategory.class);
        when(category.getKey()).thenReturn(categoryKey);
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        when(status.getStatusCategory()).thenReturn(category);
        return status;
    }

    @Test
    public void jsonContainsProjectsWithServiceDeskFlag() {
        String json = AdminPageData.toJson(
                List.of(project(1L, "SUP", "Поддержка", "service_desk"),
                        project(2L, "DEV", "Разработка", "software")),
                List.of());

        assertTrue(json.contains("{\"value\":\"SUP\",\"label\":\"Поддержка (SUP)\",\"serviceDesk\":true}"));
        assertTrue(json.contains("{\"value\":\"DEV\",\"label\":\"Разработка (DEV)\",\"serviceDesk\":false}"));
        // сортировка по названию: «Поддержка» раньше «Разработки»
        assertTrue(json.indexOf("SUP") < json.indexOf("DEV"));
    }

    @Test
    public void jsonMarksStatusesOfDoneCategory() {
        String json = AdminPageData.toJson(List.of(),
                List.of(status("3", "В работе", "indeterminate"), status("10001", "Готово", "done")));

        assertTrue(json.contains("{\"value\":\"3\",\"label\":\"В работе\",\"done\":false}"));
        assertTrue(json.contains("{\"value\":\"10001\",\"label\":\"Готово\",\"done\":true}"));
    }

    @Test
    public void jsonContainsActionCatalog() {
        String json = AdminPageData.toJson(List.of(), List.of());

        assertTrue(json.contains("\"key\":\"mention\""));
        assertTrue(json.contains("\"enabledKey\":\"action.mention.enabled\""));
        assertTrue(json.contains("\"templateKey\":\"action.closed.template.telegram\""));
        assertTrue(json.contains("\"placeholders\":[\"issueKey\",\"issueUrl\",\"summary\",\"project\",\"author\",\"status\"]"));
    }

    /** Название проекта не должно уметь закрыть script-тег страницы. */
    @Test
    public void embedEscapesClosingTagSequence() {
        String json = AdminPageData.toJson(
                List.of(project(1L, "XSS", "</script><img src=x>", "software")), List.of());

        String embedded = AdminPageData.embed(json);

        assertFalse(embedded.contains("</script><img"));
        assertTrue(embedded.contains("<\\/script>"));
        assertTrue(embedded.endsWith(";</script>"));
    }
}
