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
        when(client.findDirectChannelId("alice@example.com")).thenReturn(Optional.of("chan123"));

        sender.send(mockUser("alice@example.com"), "hello");

        verify(client).sendMessage("chan123", "hello");
    }

    /**
     * «Некуда доставить» — ошибка, а не тихий пропуск: иначе вызывающий считает
     * получателя уведомлённым и не отправит ему ничего другим способом.
     */
    @Test(expected = IllegalStateException.class)
    public void failsWhenUserNotFoundInMattermost() {
        when(client.findDirectChannelId("bob@example.com")).thenReturn(Optional.empty());

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
}
