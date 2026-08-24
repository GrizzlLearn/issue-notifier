package ru.my.impl.telegram;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationSender;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;

@Named
@ExportAsService(NotificationSender.class)
public class TelegramNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationSender.class);

    private final TelegramClient client;
    private final UserSettingsService userSettingsService;
    @SuppressWarnings("unused") // инжекция гарантирует запуск polling при старте плагина
    private final TelegramPollingService pollingService;

    @Inject
    public TelegramNotificationSender(TelegramClient client, UserSettingsService userSettingsService,
                                      TelegramPollingService pollingService) {
        this.client = client;
        this.userSettingsService = userSettingsService;
        this.pollingService = pollingService;
    }

    @Override
    public void send(ApplicationUser recipient, String message) {
        String chatId = userSettingsService.getSettings(recipient).getTelegramChatId();
        if (chatId == null || chatId.isBlank()) {
            log.warn("Telegram chat_id не задан у {}, пропускаем", recipient.getDisplayName());
            return;
        }
        client.sendMessage(chatId, message);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }
}
