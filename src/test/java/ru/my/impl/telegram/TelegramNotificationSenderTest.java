package ru.my.impl.telegram;

import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class TelegramNotificationSenderTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final UserSettingsService userSettingsService = mock(UserSettingsService.class);
    private final TelegramNotificationSender sender = new TelegramNotificationSender(client, userSettingsService);

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

    /**
     * Пустой chat_id — ошибка, а не тихий пропуск: иначе вызывающий считает
     * получателя уведомлённым, хотя не ушло ничего.
     */
    @Test(expected = IllegalStateException.class)
    public void failsWhenChatIdIsNull() {
        ApplicationUser user = mockUser();
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().build());

        sender.send(user, "hello");
    }

    @Test(expected = IllegalStateException.class)
    public void failsWhenChatIdIsBlank() {
        ApplicationUser user = mockUser();
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().telegramChatId("  ").build());

        sender.send(user, "hello");
    }

    @Test
    public void testSendPassesFormTokenToClient() {
        ApplicationUser user = mockUser();
        Map<String, String> form = Map.of("telegram.botToken", "123:ABC");
        when(userSettingsService.getSettings(user))
                .thenReturn(UserSettings.builder().telegramChatId("123456").build());

        sender.sendTest(user, "проверка", form);

        verify(client).sendMessage("123456", "проверка", form);
    }

    private static ApplicationUser mockUser() {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }
}
