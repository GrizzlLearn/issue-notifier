package ru.my.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.queue.MailQueue;
import com.atlassian.mail.queue.MailQueueItem;
import org.junit.Test;
import ru.my.model.NotificationChannel;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class EmailNotificationSenderTest {

    private final MailQueue mailQueue = mock(MailQueue.class);
    private final EmailNotificationSender sender = new EmailNotificationSender(mailQueue);

    @Test
    public void channelIsEmail() {
        assertEquals(NotificationChannel.EMAIL, sender.channel());
    }

    @Test
    public void addsItemToQueueForUserWithEmail() {
        sender.send(mockUser("alice@example.com"), "<html>body</html>");

        verify(mailQueue).addItem(any(MailQueueItem.class));
    }

    @Test
    public void skipsUserWithEmptyEmail() {
        sender.send(mockUser(""), "body");

        verify(mailQueue, never()).addItem(any());
    }

    @Test
    public void skipsUserWithNullEmail() {
        sender.send(mockUser(null), "body");

        verify(mailQueue, never()).addItem(any());
    }

    private static ApplicationUser mockUser(String email) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getEmailAddress()).thenReturn(email);
        when(user.getDisplayName()).thenReturn("Test User");
        return user;
    }
}
