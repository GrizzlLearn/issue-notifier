package ru.my.impl.email;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.Email;
import com.atlassian.mail.queue.MailQueue;
import com.atlassian.mail.queue.SingleMailQueueItem;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationSender;
import ru.my.model.NotificationChannel;
import ru.my.model.TestMessages;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;

@Named
@ExportAsService(NotificationSender.class)
public class EmailNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

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
        log.debug("Email: письмо для {} поставлено в почтовую очередь Jira ({} символов)",
                address, message.length());
    }

    /**
     * Своих настроек у канала нет, поэтому проверка — та же отправка, но своей
     * темой и телом (см. {@link TestMessages}): в ящике письмо лежит рядом с
     * настоящими уведомлениями и должно от них отличаться.
     * <p>
     * Письмо попадает в почтовую очередь Jira: успех здесь означает, что оно
     * принято в очередь, а не что дошло до ящика.
     */
    @Override
    public void sendTest(ApplicationUser recipient, String message, Map<String, String> settings) {
        String address = recipient.getEmailAddress();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "У пользователя " + recipient.getDisplayName() + " не указан email в Jira");
        }
        sendTestTo(address, message, settings);
    }

    @Override
    public void sendTestTo(String email, String message, Map<String, String> settings) {
        Email letter = new Email(email);
        letter.setSubject(TestMessages.EMAIL_SUBJECT);
        letter.setBody(message);
        letter.setMimeType("text/html");
        mailQueue.addItem(new SingleMailQueueItem(letter));
        log.debug("Email: проверочное письмо для {} поставлено в почтовую очередь Jira", email);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
