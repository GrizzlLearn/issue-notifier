package ru.my.impl.mattermost;

import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import ru.my.model.NotificationChannel;

import java.util.Map;
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
        when(client.findDirectChannelId("alice@example.com", java.util.Map.of())).thenReturn(Optional.of("chan123"));

        sender.send(mockUser("alice@example.com"), "hello");

        verify(client).sendMessage("chan123", "hello", java.util.Map.of());
    }

    /**
     * «Некуда доставить» — ошибка, а не тихий пропуск: иначе вызывающий считает
     * получателя уведомлённым и не отправит ему ничего другим способом.
     */
    @Test(expected = IllegalStateException.class)
    public void failsWhenUserNotFoundInMattermost() {
        when(client.findDirectChannelId("bob@example.com", java.util.Map.of())).thenReturn(Optional.empty());

        sender.send(mockUser("bob@example.com"), "hello");
    }

    @Test(expected = IllegalStateException.class)
    public void failsForUserWithEmptyEmail() {
        sender.send(mockUser(""), "hello");
    }

    @Test(expected = IllegalStateException.class)
    public void failsForUserWithNullEmail() {
        sender.send(mockUser(null), "hello");
    }

    @Test
    public void testSendPassesFormSettingsToClient() {
        Map<String, String> form = Map.of("mattermost.domain", "https://mm.example.com");

        sender.sendTest(mockUser("alice@example.com"), "проверка", form);

        verify(client).sendTest("alice@example.com", "проверка", form);
    }

    private static ApplicationUser mockUser(String email) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getEmailAddress()).thenReturn(email);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }
    @Test
    public void carriesSameSnapshotThroughResolutionDeliveryAndFailedCacheInvalidation() {
        Map<String, String> snapshot = Map.of("mattermost.domain", "https://old.example");
        when(client.configuration()).thenReturn(snapshot);
        when(client.findDirectChannelId("alice@example.com", snapshot)).thenReturn(Optional.of("channel"));
        doThrow(new IllegalStateException("send failed")).when(client).sendMessage("channel", "hello", snapshot);
        try {
            sender.send(mockUser("alice@example.com"), "hello");
            org.junit.Assert.fail("expected send failure");
        } catch (IllegalStateException expected) {
            verify(client).forgetChannel("alice@example.com", snapshot);
            verify(client, times(1)).configuration();
        }
    }

}
