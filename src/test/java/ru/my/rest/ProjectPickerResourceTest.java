package ru.my.rest;

import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.project.MockProject;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Пикер проектов: он спрашивает у Jira сразу доступные пользователю проекты,
 * а не перебирает все проекты инстанса с проверкой прав на каждый.
 */
@RunWith(MockitoJUnitRunner.class)
public class ProjectPickerResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private ProjectManager projectManager;
    @Mock private PermissionManager permissionManager;

    private ProjectPickerResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new ProjectPickerResource(authContext, projectManager, permissionManager);
    }

    @Test
    public void searchReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);

        assertEquals(401, resource.search("proj").getStatus());
        verify(permissionManager, never()).getProjects(any(), any(com.atlassian.jira.user.ApplicationUser.class));
    }

    @Test
    public void searchFiltersByKeyAndName() {
        loggedIn(project("PROJ", "Портал"), project("SD", "Служба поддержки"));

        assertEquals(List.of("SD"), keysOf(resource.search("поддержк")));
        assertEquals(List.of("PROJ"), keysOf(resource.search("proj")));
    }

    /** Пустой запрос — отдаём всё доступное: пикер открывают и без ввода. */
    @Test
    public void emptyQueryReturnsEverythingAvailable() {
        loggedIn(project("PROJ", "Портал"), project("SD", "Служба поддержки"));

        assertEquals(List.of("PROJ", "SD"), keysOf(resource.search(null)));
    }

    /** Права уже учтены самим getProjects — отдельной проверки на каждый проект нет. */
    @Test
    public void asksJiraForAvailableProjectsOnly() {
        loggedIn(project("PROJ", "Портал"));

        resource.search("");

        verify(permissionManager).getProjects(ProjectPermissions.BROWSE_PROJECTS, user);
        verify(projectManager, never()).getProjectObjects();
    }

    @Test
    public void searchLimitsResults() {
        Project[] many = new Project[50];
        for (int i = 0; i < many.length; i++) {
            many[i] = project("P" + i, "Проект " + i);
        }
        loggedIn(many);

        @SuppressWarnings("unchecked")
        List<PickerItemDto> items = (List<PickerItemDto>) resource.search("").getEntity();
        assertEquals(20, items.size());
    }

    @Test
    public void resolveReturns404ForProjectWithoutPermission() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        Project project = project("PROJ", "Портал");
        when(projectManager.getProjectObjByKey("PROJ")).thenReturn(project);
        when(permissionManager.hasPermission(eq(ProjectPermissions.BROWSE_PROJECTS), eq(project), eq(user)))
                .thenReturn(false);

        assertEquals(404, resource.resolve("PROJ").getStatus());
    }

    @Test
    public void resolveReturnsLabelWithKey() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        Project project = project("PROJ", "Портал");
        when(projectManager.getProjectObjByKey("PROJ")).thenReturn(project);
        when(permissionManager.hasPermission(eq(ProjectPermissions.BROWSE_PROJECTS), eq(project), eq(user)))
                .thenReturn(true);

        PickerItemDto item = (PickerItemDto) resource.resolve("PROJ").getEntity();

        assertEquals("PROJ", item.getValue());
        assertTrue(item.getLabel().contains("Портал"));
        assertTrue(item.getLabel().contains("PROJ"));
    }

    @Test
    public void resolveReturns404WhenProjectDoesNotExist() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(projectManager.getProjectObjByKey("NOPE")).thenReturn(null);

        assertEquals(404, resource.resolve("NOPE").getStatus());
    }

    private void loggedIn(Project... projects) {
        when(authContext.getLoggedInUser()).thenReturn(user);
        lenient().when(permissionManager.getProjects(ProjectPermissions.BROWSE_PROJECTS, user))
                .thenReturn(List.of(projects));
    }

    private static Project project(String key, String name) {
        MockProject project = new MockProject(1L, key);
        project.setName(name);
        return project;
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysOf(Response response) {
        return ((List<PickerItemDto>) response.getEntity()).stream()
                .map(PickerItemDto::getValue)
                .collect(java.util.stream.Collectors.toList());
    }
}
