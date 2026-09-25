package ru.my.impl.email;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.Email;
import com.atlassian.mail.queue.MailQueue;
import com.atlassian.mail.queue.SingleMailQueueItem;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.NotificationSender;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;

@Named
@ExportAsService(NotificationSender.class)
public class EmailNotificationSender implements NotificationSender {

    private static final String SUBJECT = "Jira: изменения в задаче";

    private final MailQueue mailQueue;

    @Inject
    public EmailNotificationSender(@ComponentImport MailQueue mailQueue) {
        this.mailQueue = mailQueue;
    }

    @Override
    public void send(ApplicationUser recipient, String message) {
        String address = recipient.getEmailAddress();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "У пользователя " + recipient.getDisplayName() + " не указан email в Jira");
        }
        Email email = new Email(address);
        email.setSubject(SUBJECT);
        email.setBody(message);
        email.setMimeType("text/html");
        mailQueue.addItem(new SingleMailQueueItem(email));
    }

    /**
     * Своих настроек у канала нет, поэтому проверка — обычная отправка.
     * Письмо попадает в почтовую очередь Jira: успех здесь означает, что оно
     * принято в очередь, а не что дошло до ящика.
     */
    @Override
    public void sendTest(ApplicationUser recipient, String message, Map<String, String> settings) {
        send(recipient, message);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
