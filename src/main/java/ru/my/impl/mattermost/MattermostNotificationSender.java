package ru.my.impl.mattermost;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationSender;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;

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
        String email = recipient.getEmailAddress();
        if (email == null || email.isBlank()) {
            log.debug("Нет email у пользователя {}, пропускаем", recipient.getDisplayName());
            return;
        }
        client.findDirectChannelId(email)
              .ifPresentOrElse(
                      channelId -> client.sendMessage(channelId, message),
                      () -> log.warn("Пользователь {} не найден в Mattermost", email));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.MATTERMOST;
    }
}
