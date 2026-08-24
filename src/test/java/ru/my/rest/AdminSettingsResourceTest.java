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
    public void putReturns400ForInvalidBooleanValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(400, resource.set(Map.of("email.enabled", "yes")).getStatus());
        assertEquals(400, resource.set(Map.of("mattermost.enabled", "1")).getStatus());
        assertEquals(400, resource.set(Map.of("telegram.enabled", "")).getStatus());
    }

    @Test
    public void putAcceptsValidBooleanValues() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(204, resource.set(Map.of("email.enabled", "true")).getStatus());
        assertEquals(204, resource.set(Map.of("mattermost.enabled", "false")).getStatus());
    }

    @Test
    public void putIgnoresUnknownKeys() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("unknown.key", "value", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq("unknown.key"), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    // --- C4: маскировка секретов ---

    @Test
    public void getReturnsMaskedValueForNonEmptySecret() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get("mattermost.token", "")).thenReturn("real-token-value");

        Response response = resource.get();

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("***", body.get("mattermost.token"));
    }

    @Test
    public void getReturnsEmptyStringForUnsetSecret() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        // значение по умолчанию "" — маска не применяется, токен не задан
        when(adminSettingsService.get("telegram.botToken", "")).thenReturn("");

        Response response = resource.get();

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("", body.get("telegram.botToken"));
    }

    @Test
    public void putDoesNotOverwriteSecretWithMaskValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        // GET вернул "***", фронт не изменил поле и отправил его обратно
        resource.set(Map.of("mattermost.token", "***", "email.enabled", "true"));

        verify(adminSettingsService, never()).set(eq("mattermost.token"), any());
        verify(adminSettingsService).set("email.enabled", "true");
    }

    @Test
    public void putDoesNotOverwriteSecretWithBlankValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("mattermost.token", "", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq("mattermost.token"), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    @Test
    public void putSavesSecretWhenNewRealValueProvided() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("telegram.botToken", "123456:ABC-DEF"));

        verify(adminSettingsService).set("telegram.botToken", "123456:ABC-DEF");
    }

    @Test
    public void putAllowsClearingNonSecretFieldWithEmptyString() {
        // mattermost.domain — не секрет, пустая строка должна сохраниться (очистка поля)
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("mattermost.domain", ""));

        verify(adminSettingsService).set("mattermost.domain", "");
    }

    @Test
    public void getReturnsBotIdAsPlainText() {
        // mattermost.botId — публичный идентификатор, не секрет, маскировать не надо
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get("mattermost.botId", "")).thenReturn("bot-abc-123");

        Response response = resource.get();

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("bot-abc-123", body.get("mattermost.botId"));
    }
}
