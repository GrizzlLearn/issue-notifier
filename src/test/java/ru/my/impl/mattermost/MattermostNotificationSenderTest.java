package ru.my.impl.mattermost;

import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import ru.my.model.NotificationChannel;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class MattermostNotificationSenderTest {

    private final MattermostClient client = mock(MattermostClient.class);
    private final MattermostNotificationSender sender = new MattermostNotificationSender(client);

    @Test
    public void channelIsMattermost() {
        assertEquals(NotificationChannel.MATTERMOST, sender.channel());
    }

    @Test
    public void sendsToDirectChannelWhenUserFound() {
        when(client.findDirectChannelId("alice@example.com")).thenReturn(Optional.of("chan123"));

        sender.send(mockUser("alice@example.com"), "hello");

        verify(client).sendMessage("chan123", "hello");
    }

    @Test
    public void skipsWhenUserNotFoundInMattermost() {
        when(client.findDirectChannelId("bob@example.com")).thenReturn(Optional.empty());

        sender.send(mockUser("bob@example.com"), "hello");

        verify(client, never()).sendMessage(any(), any());
    }

    @Test
    public void skipsUserWithEmptyEmail() {
        sender.send(mockUser(""), "hello");

        verify(client, never()).findDirectChannelId(any());
    }

    @Test
    public void skipsUserWithNullEmail() {
        sender.send(mockUser(null), "hello");

        verify(client, never()).findDirectChannelId(any());
    }

    private static ApplicationUser mockUser(String email) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getEmailAddress()).thenReturn(email);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }
}
