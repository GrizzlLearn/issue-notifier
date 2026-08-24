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
import ru.my.impl.ChannelKeys;

import javax.ws.rs.core.Response;
import java.util.Map;

import static org.junit.Assert.*;
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
            assertTrue("Ключ отсутствует в ответе: " + key, body.containsKey(key));
        }
    }

    @Test
    public void getReturnsEmptyStringForSecretRegardlessOfStoredValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_TOKEN, "")).thenReturn("real-token");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        // секрет никогда не возвращается — write-only
        assertEquals("", body.get(ChannelKeys.MATTERMOST_TOKEN));
    }

    @Test
    public void getReturnsIsTrueWhenSecretIsSet() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_TOKEN, "")).thenReturn("real-token");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        assertEquals("true", body.get(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
    }

    @Test
    public void getReturnsIsFalseWhenSecretIsNotSet() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        assertEquals("false", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
        assertEquals("", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN));
    }

    @Test
    public void getReturnsBotIdAsPlainText() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_BOT_ID, "")).thenReturn("bot-abc-123");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        // botId — публичный идентификатор, не секрет
        assertEquals("bot-abc-123", body.get(ChannelKeys.MATTERMOST_BOT_ID));
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
                ChannelKeys.MATTERMOST_TOKEN, "secret123"
        ));

        assertEquals(204, response.getStatus());
        verify(adminSettingsService).set("email.enabled", "true");
        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_TOKEN, "secret123");
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

    @Test
    public void putDoesNotOverwriteSecretWithBlankValue() {
        // пустое значение секрета = "не менять существующий" (write-only семантика)
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_TOKEN, "", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq(ChannelKeys.MATTERMOST_TOKEN), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    @Test
    public void putSavesSecretWhenNewValueProvided() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123456:ABC-DEF"));

        verify(adminSettingsService).set(ChannelKeys.TELEGRAM_BOT_TOKEN, "123456:ABC-DEF");
    }

    @Test
    public void putIgnoresIsSetKeys() {
        // .isSet ключи read-only — PUT должен их игнорировать
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX, "true"));

        verify(adminSettingsService, never()).set(anyString(), anyString());
    }

    @Test
    public void putAllowsClearingNonSecretFieldWithEmptyString() {
        // не-секретные поля (domain) очищать можно
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_DOMAIN, ""));

        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_DOMAIN, "");
    }
}
