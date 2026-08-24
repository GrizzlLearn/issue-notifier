package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
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

    private UserSettingsResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new UserSettingsResource(authContext, userSettingsService);
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
        assertEquals(401, resource.save(new UserSettingsDto(true, List.of("*"), List.of(), null)).getStatus());
    }

    @Test
    public void putReturns400WhenBodyIsNull() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        assertEquals(400, resource.save(null).getStatus());
    }

    @Test
    public void putSavesSettingsAndReturns204() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(false, List.of("PROJ"), List.of("EMAIL"), null);

        Response response = resource.save(dto);

        assertEquals(204, response.getStatus());
        verify(userSettingsService).saveSettings(eq(user), argThat(s ->
                !s.isEnabled()
                && s.getProjects().contains("PROJ")
                && s.getChannels().contains(NotificationChannel.EMAIL)
        ));
    }

    @Test
    public void putIgnoresUnknownChannelNames() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        UserSettingsDto dto = new UserSettingsDto(true, List.of("*"), List.of("UNKNOWN", "EMAIL"), null);

        resource.save(dto);

        verify(userSettingsService).saveSettings(eq(user), argThat(s ->
                s.getChannels().size() == 1
                && s.getChannels().contains(NotificationChannel.EMAIL)
        ));
    }
}
