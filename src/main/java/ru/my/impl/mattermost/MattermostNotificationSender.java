package ru.my.impl.mattermost;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationSender;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;

@Named
@ExportAsService(NotificationSender.class)
public class MattermostNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MattermostNotificationSender.class);

    private final MattermostClient client;

    @Inject
    public MattermostNotificationSender(MattermostClient client) {
        this.client = client;
    }

    @Override
    public void send(ApplicationUser recipient, String message) {
        String email = email(recipient);
        String channelId = client.findDirectChannelId(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Пользователь с email " + email + " не найден в Mattermost"));
        log.debug("Mattermost: уведомление для {} → канал {} ({} символов)",
                recipient.getName(), channelId, message.length());
        sendOrForget(email, channelId, message);
    }

    @Override
    public void sendTest(ApplicationUser recipient, String message, Map<String, String> settings) {
        client.sendTest(email(recipient), message, settings);
    }

    /** Mattermost и так адресует по email, поэтому пользователь Jira здесь не нужен. */
    @Override
    public void sendTestTo(String email, String message, Map<String, String> settings) {
        client.sendTest(email, message, settings);
    }

    /** Получателя в Mattermost ищут по email из Jira — без него отправлять некуда. */
    private static String email(ApplicationUser recipient) {
        String email = recipient.getEmailAddress();
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "У пользователя " + recipient.getDisplayName() + " не указан email в Jira");
        }
        return email;
    }

    /**
     * id канала кешируется, поэтому при сбое отправки его надо забыть: пользователя
     * могли удалить или пересоздать, и иначе канал остался бы битым до истечения кеша.
     */
    private void sendOrForget(String email, String channelId, String message) {
        try {
            client.sendMessage(channelId, message);
        } catch (RuntimeException e) {
            log.debug("Mattermost: отправка в канал {} не удалась, кеш канала сброшен: {}",
                    channelId, e.getMessage());
            client.forgetChannel(email);
            throw e;
        }
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.MATTERMOST;
    }
}
