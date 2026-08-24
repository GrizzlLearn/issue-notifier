package ru.my.impl;

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

import javax.inject.Inject;
import javax.inject.Named;

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
            log.debug("Нет email у пользователя {}, пропускаем", recipient.getDisplayName());
            return;
        }
        Email email = new Email(address);
        email.setSubject(SUBJECT);
        email.setBody(message);
        email.setMimeType("text/html");
        mailQueue.addItem(new SingleMailQueueItem(email));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
