package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;

import javax.ws.rs.core.Response;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class AdminSettingsResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private AdminSettingsService adminSettingsService;

    private AdminSettingsResource resource;
    private final MockApplicationUser admin = new MockApplicationUser("admin");
    private final MockApplicationUser regular = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new AdminSettingsResource(authContext, globalPermissionManager, adminSettingsService);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, regular)).thenReturn(false);
        when(adminSettingsService.get(anyString(), anyString())).thenReturn("");
    }

    // --- GET ---

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

    @Test
    public void getReturnsAllKnownKeysForAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals(AdminSettingsResource.KNOWN_KEYS.size(), body.size());
        for (String key : AdminSettingsResource.KNOWN_KEYS) {
            assertEquals(true, body.containsKey(key));
        }
    }

    // --- PUT ---

    @Test
    public void putReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.set(Map.of("email.enabled", "true")).getStatus());
    }

    @Test
    public void putReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.set(Map.of("email.enabled", "true")).getStatus());
    }

    @Test
    public void putReturns400WhenBodyIsEmpty() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.set(null).getStatus());
        assertEquals(400, resource.set(Map.of()).getStatus());
    }

    @Test
    public void putSavesKnownKeysAndReturns204() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.set(Map.of(
                "email.enabled", "true",
                "mattermost.token", "secret123"
        ));

        assertEquals(204, response.getStatus());
        verify(adminSettingsService).set("email.enabled", "true");
        verify(adminSettingsService).set("mattermost.token", "secret123");
    }

    @Test
    public void putIgnoresUnknownKeys() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("unknown.key", "value", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq("unknown.key"), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }
}
