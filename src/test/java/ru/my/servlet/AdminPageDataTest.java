package ru.my.servlet;

import com.atlassian.jira.action.issue.customfields.MockCustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.MockCustomField;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.project.MockProject;
import com.atlassian.jira.project.ProjectCategory;
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

    private static CustomField customField(String id, String name, String typeKey, List<Project> projects) {
        MockCustomFieldType type = new MockCustomFieldType();
        type.setKey(typeKey);
        return new MockCustomField(id, name, type).setAssociatedProjectObjects(projects);
    }

    @Test
    public void jsonContainsProjectsWithServiceDeskFlag() {
        String json = AdminPageData.toJson(
                List.of(project(1L, "SUP", "Поддержка", "service_desk"),
                        project(2L, "DEV", "Разработка", "software")),
                List.of(), List.of(), List.of());

        assertTrue(json.contains("{\"value\":\"SUP\",\"label\":\"Поддержка (SUP)\",\"serviceDesk\":true,\"category\":null}"));
        assertTrue(json.contains("{\"value\":\"DEV\",\"label\":\"Разработка (DEV)\",\"serviceDesk\":false,\"category\":null}"));
        // сортировка по названию: «Поддержка» раньше «Разработки»
        assertTrue(json.indexOf("SUP") < json.indexOf("DEV"));
    }

    @Test
    public void jsonMarksStatusesOfDoneCategory() {
        String json = AdminPageData.toJson(List.of(),
                List.of(status("3", "В работе", "indeterminate"), status("10001", "Готово", "done")),
                List.of(), List.of());

        assertTrue(json.contains("{\"value\":\"3\",\"label\":\"В работе\",\"done\":false}"));
        assertTrue(json.contains("{\"value\":\"10001\",\"label\":\"Готово\",\"done\":true}"));
    }

    @Test
    public void jsonContainsActionCatalog() {
        String json = AdminPageData.toJson(List.of(), List.of(), List.of(), List.of());

        assertTrue(json.contains("\"key\":\"mention\""));
        assertTrue(json.contains("\"enabledKey\":\"action.mention.enabled\""));
        assertTrue(json.contains("\"templateKey\":\"action.closed.template.telegram\""));
        assertTrue(json.contains("\"placeholders\":[\"issueKey\",\"issueUrl\",\"summary\",\"project\",\"author\",\"status\"]"));
    }

    /** В списке получателей нужны только поля с пользователями. */
    @Test
    public void jsonContainsOnlyUserPickerFields() {
        String json = AdminPageData.toJson(List.of(), List.of(), List.of(
                customField("customfield_10100", "Согласующий",
                        "com.atlassian.jira.plugin.system.customfieldtypes:userpicker",
                        List.of(project(1L, "SUP", "Поддержка", "service_desk"))),
                customField("customfield_10200", "Срок",
                        "com.atlassian.jira.plugin.system.customfieldtypes:datepicker", List.of())),
                List.of());

        assertTrue(json.contains("{\"value\":\"customfield_10100\",\"label\":\"Согласующий\",\"scope\":\"SUP\"}"));
        assertFalse(json.contains("customfield_10200"));
    }

    /** Поле без привязки к проектам доступно везде. */
    @Test
    public void globalUserPickerFieldIsMarkedAsAllProjects() {
        String json = AdminPageData.toJson(List.of(), List.of(), List.of(
                customField("customfield_10300", "Ответственный",
                        "com.atlassian.jira.plugin.system.customfieldtypes:multiuserpicker", List.of())),
                List.of());

        assertTrue(json.contains("\"scope\":\"все проекты\""));
    }

    @Test
    public void jsonExposesRecipientsKeyOnlyForConfigurableAction() {
        String json = AdminPageData.toJson(List.of(), List.of(), List.of(), List.of());

        assertTrue(json.contains("\"recipientsKey\":\"action.closed.recipients\""));
        assertTrue(json.contains("\"recipientsKey\":null"));
    }

    /** Категория проекта нужна экрану, чтобы разворачивать её в список проектов. */
    @Test
    public void jsonContainsCategoriesAndProjectCategory() {
        ProjectCategory category = mock(ProjectCategory.class);
        when(category.getId()).thenReturn(10000L);
        when(category.getName()).thenReturn("Портал");
        MockProject portal = new MockProject(1L, "SUP", "Поддержка");
        portal.setProjectCategory(category);

        String json = AdminPageData.toJson(
                List.of(portal, project(2L, "DEV", "Разработка", "software")),
                List.of(), List.of(), List.of(category));

        assertTrue(json.contains("\"categories\":[{\"value\":\"10000\",\"label\":\"Портал\"}]"));
        assertTrue(json.contains("\"SUP\",\"label\":\"Поддержка (SUP)\",\"serviceDesk\":false,\"category\":\"10000\""));
        assertTrue(json.contains("\"DEV\",\"label\":\"Разработка (DEV)\",\"serviceDesk\":false,\"category\":null"));
    }

    /** Название проекта не должно уметь закрыть script-тег страницы. */
    @Test
    public void embedEscapesClosingTagSequence() {
        String json = AdminPageData.toJson(
                List.of(project(1L, "XSS", "</script><img src=x>", "software")),
                List.of(), List.of(), List.of());

        String embedded = AdminPageData.embed(json);

        assertFalse(embedded.contains("</script><img"));
        assertTrue(embedded.contains("<\\/script>"));
        assertTrue(embedded.endsWith(";</script>"));
    }
}
