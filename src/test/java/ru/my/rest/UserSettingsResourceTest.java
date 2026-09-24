package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import javax.ws.rs.core.Response;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class UserSettingsResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private UserSettingsService userSettingsService;
    @Mock private AdminSettingsService adminSettingsService;

    private UserSettingsResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new UserSettingsResource(authContext, userSettingsService, adminSettingsService);
        when(adminSettingsService.get(anyString(), anyString())).thenReturn("");
    }

    @Test
    public void getReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.get().getStatus());
    }

    @Test
    public void getReturnsSettingsForLoggedInUser() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettings settings = UserSettings.defaultSettings();
        when(userSettingsService.getSettings(user)).thenReturn(settings);

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        UserSettingsDto dto = (UserSettingsDto) response.getEntity();
        assertEquals(List.of("*"), dto.getProjects());
        assertEquals(List.of(), dto.getChannels());
    }

    @Test
    public void putReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.save(new UserSettingsDto(true, List.of("*"), List.of(), null, null)).getStatus());
    }

    @Test
    public void putReturns400WhenBodyIsNull() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        assertEquals(400, resource.save(null).getStatus());
    }

    @Test
    public void putSavesSettingsAndReturns204() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(false, List.of("PROJ"), List.of("EMAIL"), null, null);

        Response response = resource.save(dto);

        assertEquals(204, response.getStatus());
        verify(userSettingsService).saveSettings(eq(user), argThat(s ->
                !s.isEnabled()
                && s.getProjects().contains("PROJ")
                && s.getChannels().contains(NotificationChannel.EMAIL)
        ));
    }

    /** chat_id правит пользователь руками; мусор в нём иначе виден только в логах отправки. */
    @Test
    public void putReturns400WhenChatIdIsNotNumeric() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(true, List.of("*"), List.of(), "@mychat", null);

        assertEquals(400, resource.save(dto).getStatus());
        verify(userSettingsService, never()).saveSettings(any(), any());
    }

    @Test
    public void putAcceptsNegativeChatIdOfGroupChat() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(true, List.of("*"), List.of(), "-1001234567", null);

        assertEquals(204, resource.save(dto).getStatus());
    }

    @Test
    public void putReturns400WhenTooManyProjects() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        List<String> projects = java.util.stream.IntStream.range(0, UserSettingsResource.MAX_PROJECTS + 1)
                .mapToObj(i -> "P" + i)
                .collect(java.util.stream.Collectors.toList());

        assertEquals(400, resource.save(
                new UserSettingsDto(true, projects, List.of(), null, null)).getStatus());
        verify(userSettingsService, never()).saveSettings(any(), any());
    }

    @Test
    public void putIgnoresUnknownChannelNames() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(true, List.of("*"), List.of("UNKNOWN", "EMAIL"), null, null);

        resource.save(dto);

        verify(userSettingsService).saveSettings(eq(user), argThat(s ->
                s.getChannels().size() == 1
                && s.getChannels().contains(NotificationChannel.EMAIL)
        ));
    }
}
