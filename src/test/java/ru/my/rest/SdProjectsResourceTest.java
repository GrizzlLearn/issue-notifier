package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.MockProject;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class SdProjectsResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private ProjectManager projectManager;

    private SdProjectsResource resource;
    private final MockApplicationUser admin = new MockApplicationUser("admin");
    private final MockApplicationUser regular = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new SdProjectsResource(authContext, globalPermissionManager, projectManager);
    }

    private static MockProject project(long id, String key, String name, String typeKey) {
        MockProject p = new MockProject(id, key, name);
        p.setProjectTypeKey(typeKey);
        return p;
    }

    @Test
    public void listReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, regular)).thenReturn(false);

        assertEquals(403, resource.list().getStatus());
    }

    @Test
    public void listReturnsOnlyServiceDeskProjectsSortedByName() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        List<Project> all = List.of(
                project(1L, "BIZ", "Бизнес", "business"),
                project(2L, "SUP", "Поддержка", "service_desk"),
                project(3L, "DEV", "Разработка", "software"),
                project(4L, "HELP", "Helpdesk", "service_desk"));
        doReturn(all).when(projectManager).getProjectObjects();

        Response response = resource.list();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<PickerItemDto> items = (List<PickerItemDto>) response.getEntity();
        assertEquals(2, items.size());
        assertEquals("HELP", items.get(0).getValue());
        assertEquals("Helpdesk (HELP)", items.get(0).getLabel());
        assertEquals("SUP", items.get(1).getValue());
    }
}
