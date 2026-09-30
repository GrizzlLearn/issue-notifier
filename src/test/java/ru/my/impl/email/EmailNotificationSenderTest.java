package ru.my.impl.email;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.queue.MailQueue;
import com.atlassian.mail.queue.MailQueueItem;
import com.atlassian.mail.server.MailServerManager;
import com.atlassian.mail.server.SMTPMailServer;
import org.junit.Test;
import ru.my.model.NotificationChannel;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class EmailNotificationSenderTest {

    private final MailQueue mailQueue = mock(MailQueue.class);
    private final MailServerManager mailServerManager = mock(MailServerManager.class);
    private final EmailNotificationSender sender =
            new EmailNotificationSender(mailQueue, mailServerManager);

    /** Проверка канала требует настроенной исходящей почты — по умолчанию она есть. */
    @org.junit.Before
    public void smtpConfigured() {
        when(mailServerManager.getDefaultSMTPMailServer()).thenReturn(mock(SMTPMailServer.class));
    }

    @Test
    public void channelIsEmail() {
        assertEquals(NotificationChannel.EMAIL, sender.channel());
    }

    @Test
    public void addsItemToQueueForUserWithEmail() {
        sender.send(mockUser("alice@example.com"), "<html>body</html>");

        verify(mailQueue).addItem(any(MailQueueItem.class));
    }

    @Test(expected = IllegalStateException.class)
    public void failsForUserWithEmptyEmail() {
        sender.send(mockUser(""), "body");
    }

    @Test(expected = IllegalStateException.class)
    public void failsForUserWithNullEmail() {
        sender.send(mockUser(null), "body");
    }

    /** Своих настроек у канала нет — проверка это обычная постановка в очередь. */
    @Test
    public void testSendQueuesMessage() {
        sender.sendTest(mockUser("alice@example.com"), "проверка", Map.of());

        verify(mailQueue).addItem(any(MailQueueItem.class));
    }

    private static ApplicationUser mockUser(String email) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getEmailAddress()).thenReturn(email);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }

    /**
     * Без исходящего SMTP-сервера проверка канала должна падать: {@code addItem}
     * успешен и без почты, и раньше проверка подтверждала работу неработающей
     * почты, а запрет «не включить канал без проверки» был формальным (С2).
     */
    @Test
    public void testFailsWhenSmtpServerIsNotConfigured() {
        when(mailServerManager.getDefaultSMTPMailServer()).thenReturn(null);

        try {
            sender.sendTestTo("alice@example.com", "проверка", Map.of());
            org.junit.Assert.fail("ожидали отказ из-за ненастроенной почты");
        } catch (IllegalStateException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("SMTP"));
        }
        verify(mailQueue, never()).addItem(any(MailQueueItem.class));
    }
}
