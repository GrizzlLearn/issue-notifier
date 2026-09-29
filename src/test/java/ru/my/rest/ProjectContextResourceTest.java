package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationAction;
import ru.my.model.ProjectContext;
import ru.my.model.ProjectContexts;

import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ProjectContextResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private AdminSettingsService adminSettingsService;

    private ProjectContextResource resource;
    private final MockApplicationUser admin = new MockApplicationUser("admin");
    private final MockApplicationUser regular = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new ProjectContextResource(authContext, globalPermissionManager, adminSettingsService);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
    }

    private static ProjectContextDto dto(String id, String name, List<String> projects, List<String> categories) {
        ProjectContextDto dto = new ProjectContextDto();
        dto.setId(id);
        dto.setName(name);
        dto.setProjects(projects);
        dto.setCategories(categories);
        return dto;
    }

    /** Значение, записанное в настройки последним PUT-ом. */
    private List<ProjectContext> saved() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(adminSettingsService).set(eq(ProjectContexts.KEY), value.capture());
        return ProjectContexts.parse(value.getValue());
    }

    @Test
    public void getReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.get().getStatus());
    }

    @Test
    public void getReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.get().getStatus());
    }

    /** Свежая установка: контекстов нет, но «Остальные проекты» уже есть. */
    @Test
    public void getReturnsDefaultContextOnEmptySettings() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ProjectContexts.KEY, "")).thenReturn("");

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<ProjectContextDto> body = (List<ProjectContextDto>) response.getEntity();
        assertEquals(1, body.size());
        assertEquals(ProjectContexts.DEFAULT_ID, body.get(0).getId());
    }

    @Test
    public void putReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.save(List.of()).getStatus());
        verify(adminSettingsService, never()).set(anyString(), any());
    }

    @Test
    public void putReturns400WhenBodyIsNull() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.save(null).getStatus());
    }

    @Test
    public void putAssignsIdToNewContext() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(204, resource.save(List.of(
                dto(null, "Категория А", List.of("HELP"), List.of()))).getStatus());

        List<ProjectContext> contexts = saved();
        assertEquals(2, contexts.size());
        assertTrue("id выдаёт сервер", contexts.get(0).id().matches("[0-9a-f]{8}"));
        assertEquals("Категория А", contexts.get(0).name());
        assertTrue(contexts.get(1).isDefault());
    }

    /** id существующего контекста должен сохраниться, иначе его настройки потеряются. */
    @Test
    public void putKeepsExistingId() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.save(List.of(dto("a1b2c3d4", "Портал", List.of("HELP"), List.of())));

        assertEquals("a1b2c3d4", saved().get(0).id());
    }

    @Test
    public void putReturns400WhenNameIsEmpty() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(400, resource.save(List.of(dto(null, "  ", List.of("HELP"), List.of()))).getStatus());
        verify(adminSettingsService, never()).set(anyString(), any());
    }

    @Test
    public void putReturns400WhenNameIsTooLong() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        String name = "и".repeat(ProjectContextResource.MAX_NAME + 1);

        assertEquals(400, resource.save(List.of(dto(null, name, List.of(), List.of()))).getStatus());
    }

    @Test
    public void putReturns400WhenProjectIsInTwoContexts() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.save(List.of(
                dto(null, "Первый", List.of("HELP"), List.of()),
                dto(null, "Второй", List.of("HELP"), List.of())));

        assertEquals(400, response.getStatus());
        verify(adminSettingsService, never()).set(anyString(), any());
    }

    @Test
    public void putReturns400WhenCategoryIsInTwoContexts() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.save(List.of(
                dto(null, "Первый", List.of(), List.of("10000")),
                dto(null, "Второй", List.of(), List.of("10000"))));

        assertEquals(400, response.getStatus());
    }

    @Test
    public void putReturns400WhenTooManyContexts() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        List<ProjectContextDto> body = new ArrayList<>();
        for (int i = 0; i <= ProjectContextResource.MAX_CONTEXTS; i++) {
            body.add(dto(null, "Контекст " + i, List.of("P" + i), List.of()));
        }

        assertEquals(400, resource.save(body).getStatus());
    }

    @Test
    public void putReturns400ForUnknownAction() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        ProjectContextDto dto = dto(null, "Контекст", List.of("HELP"), List.of());
        dto.setActions(Map.of("mentoin", new ProjectContextDto.ActionDto(true, "")));

        assertEquals(400, resource.save(List.of(dto)).getStatus());
    }

    @Test
    public void putSavesActionSettings() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        ProjectContextDto dto = dto(null, "Портал", List.of("HELP"), List.of());
        dto.setActions(Map.of(NotificationAction.CLOSED.key(),
                new ProjectContextDto.ActionDto(true, "reporter,assignee")));
        dto.setClosedStatuses(Map.of("HELP", List.of("10001")));

        resource.save(List.of(dto));

        ProjectContext context = saved().get(0);
        assertTrue(context.isEnabled(NotificationAction.CLOSED));
        assertEquals("reporter,assignee", context.recipients(NotificationAction.CLOSED));
        assertTrue(context.isClosing("HELP", "10001"));
    }

    /** «Остальные проекты» не пришли в теле — их настройки должны сохраниться. */
    @Test
    public void putKeepsStoredDefaultContextWhenItIsAbsentFromBody() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        String stored = ProjectContexts.format(List.of(new ProjectContext(
                ProjectContexts.DEFAULT_ID, "Остальные проекты", java.util.Set.of(), java.util.Set.of(),
                Map.of(NotificationAction.MENTION.key(), new ProjectContext.ActionSetting(true, "")),
                Map.of())));
        when(adminSettingsService.get(ProjectContexts.KEY, "")).thenReturn(stored);

        resource.save(List.of(dto(null, "Портал", List.of("HELP"), List.of())));

        ProjectContext defaultContext = ProjectContexts.byId(saved(), ProjectContexts.DEFAULT_ID);
        assertNotNull(defaultContext);
        assertTrue(defaultContext.isEnabled(NotificationAction.MENTION));
    }

    @Test
    public void putSavesDefaultContextSettingsFromBody() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        ProjectContextDto dto = dto(ProjectContexts.DEFAULT_ID, "Остальные проекты", List.of(), List.of());
        dto.setActions(Map.of(NotificationAction.ASSIGNED.key(),
                new ProjectContextDto.ActionDto(true, "")));

        resource.save(List.of(dto));

        ProjectContext defaultContext = ProjectContexts.byId(saved(), ProjectContexts.DEFAULT_ID);
        assertNotNull(defaultContext);
        assertTrue(defaultContext.isEnabled(NotificationAction.ASSIGNED));
    }

    @Test
    public void putReturns400WhenDefaultContextIsListedTwice() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.save(List.of(
                dto(ProjectContexts.DEFAULT_ID, "Остальные", List.of(), List.of()),
                dto(ProjectContexts.DEFAULT_ID, "Остальные", List.of(), List.of())));

        assertEquals(400, response.getStatus());
    }
}
