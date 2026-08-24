package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.MockApplicationUser;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.UserNotificationSettingsEntity;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class UserSettingsServiceTest {

    @Mock
    private ActiveObjects ao;

    private UserSettingsServiceImpl service;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new UserSettingsServiceImpl(ao);
    }

    @Test
    public void returnsDefaultsWhenNoRecordExists() {
        when(ao.find(eq(UserNotificationSettingsEntity.class), any(Query.class)))
                .thenReturn(new UserNotificationSettingsEntity[0]);

        UserSettings result = service.getSettings(new MockApplicationUser("jdoe"));

        assertTrue(result.isEnabled());
        assertEquals(1, result.getProjects().size());
        assertEquals("*", result.getProjects().get(0));
        assertTrue(result.getChannels().isEmpty());
    }

    @Test
    public void readsPersistedSettings() {
        UserNotificationSettingsEntity entity = mock(UserNotificationSettingsEntity.class);
        when(entity.isEnabled()).thenReturn(false);
        when(entity.getProjectsRaw()).thenReturn("PROJ,TEST");
        when(entity.getChannelsRaw()).thenReturn("MATTERMOST,EMAIL");
        when(entity.getTelegramChatId()).thenReturn(null);
        when(ao.find(eq(UserNotificationSettingsEntity.class), any(Query.class)))
                .thenReturn(new UserNotificationSettingsEntity[]{entity});

        UserSettings result = service.getSettings(new MockApplicationUser("jdoe"));

        assertFalse(result.isEnabled());
        assertEquals(2, result.getProjects().size());
        assertTrue(result.getProjects().contains("PROJ"));
        assertTrue(result.getProjects().contains("TEST"));
        assertTrue(result.getChannels().contains(NotificationChannel.MATTERMOST));
        assertTrue(result.getChannels().contains(NotificationChannel.EMAIL));
    }

    @Test
    public void handlesEmptyChannelsRaw() {
        UserNotificationSettingsEntity entity = mock(UserNotificationSettingsEntity.class);
        when(entity.isEnabled()).thenReturn(true);
        when(entity.getProjectsRaw()).thenReturn("*");
        when(entity.getChannelsRaw()).thenReturn("");
        when(ao.find(eq(UserNotificationSettingsEntity.class), any(Query.class)))
                .thenReturn(new UserNotificationSettingsEntity[]{entity});

        UserSettings result = service.getSettings(new MockApplicationUser("jdoe"));

        assertTrue(result.getChannels().isEmpty());
    }
}
