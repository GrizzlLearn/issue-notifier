package ru.my.model;

import com.atlassian.jira.user.ApplicationUser;
import java.util.List;

/** Ошибка части доставок с получателями, которым уже ушло приоритетное сообщение. */
public class NotificationDeliveryException extends IllegalStateException {
    private final List<ApplicationUser> notifiedRecipients;

    public NotificationDeliveryException(List<Exception> failures, List<ApplicationUser> notifiedRecipients) {
        super("Не все уведомления события отправлены");
        this.notifiedRecipients = List.copyOf(notifiedRecipients);
        failures.forEach(this::addSuppressed);
    }

    public List<ApplicationUser> getNotifiedRecipients() {
        return notifiedRecipients;
    }
}
