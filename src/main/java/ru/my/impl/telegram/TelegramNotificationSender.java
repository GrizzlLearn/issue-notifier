package ru.my.impl.telegram;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.my.api.NotificationSender;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;

@Named
@ExportAsService(NotificationSender.class)
public class TelegramNotificationSender implements NotificationSender {

    private final TelegramClient client;
    private final UserSettingsService userSettingsService;

    @Inject
    public TelegramNotificationSender(TelegramClient client, UserSettingsService userSettingsService) {
        this.client = client;
        this.userSettingsService = userSettingsService;
    }

    @Override
    public void send(ApplicationUser recipient, String message) {
        client.sendMessage(chatId(recipient), message);
    }

    @Override
    public void sendTest(ApplicationUser recipient, String message, Map<String, String> settings) {
        client.sendMessage(chatId(recipient), message, settings);
    }

    /**
     * Chat ID пользователь указывает в своих настройках сам — бот не может начать
     * переписку первым. Без него отправлять некуда.
     */
    private String chatId(ApplicationUser recipient) {
        String chatId = userSettingsService.getSettings(recipient).getTelegramChatId();
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalStateException("У " + recipient.getDisplayName()
                    + " не указан Telegram Chat ID в настройках уведомлений");
        }
        return chatId;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }
}
