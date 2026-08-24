package ru.my.impl.telegram;

import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class TelegramNotificationSenderTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final UserSettingsService userSettingsService = mock(UserSettingsService.class);
    private final TelegramPollingService pollingService = mock(TelegramPollingService.class);
    private final TelegramNotificationSender sender = new TelegramNotificationSender(client, userSettingsService, pollingService);

    @Test
    public void channelIsTelegram() {
        assertEquals(NotificationChannel.TELEGRAM, sender.channel());
    }

    @Test
    public void sendsMessageWhenChatIdIsSet() {
        ApplicationUser user = mockUser();
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().telegramChatId("123456").build());

        sender.send(user, "hello");

        verify(client).sendMessage("123456", "hello");
    }

    @Test
    public void skipsWhenChatIdIsNull() {
        ApplicationUser user = mockUser();
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().build());

        sender.send(user, "hello");

        verify(client, never()).sendMessage(any(), any());
    }

    @Test
    public void skipsWhenChatIdIsBlank() {
        ApplicationUser user = mockUser();
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().telegramChatId("  ").build());

        sender.send(user, "hello");

        verify(client, never()).sendMessage(any(), any());
    }

    private static ApplicationUser mockUser() {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }
}
