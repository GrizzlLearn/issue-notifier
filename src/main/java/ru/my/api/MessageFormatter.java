package ru.my.api;

import com.atlassian.jira.issue.Issue;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

/**
 * Форматирует нейтральный {@link DiffResult} в текст сообщения для конкретного канала.
 * Каждый канал имеет свою реализацию с нужной разметкой:
 * Mattermost — markdown diff-блок, Telegram — HTML, Email — HTML со стилями.
 */
public interface MessageFormatter {

    /**
     * Формирует текст уведомления.
     *
     * @param issue задача, в которой произошли изменения
     * @param diff  список изменённых полей
     * @return готовая строка для отправки через соответствующий {@link NotificationSender}
     */
    String format(Issue issue, DiffResult diff);

    /**
     * Канал, для которого предназначен этот форматтер.
     * Используется для построения маппинга в {@link ru.my.impl.NotificationServiceImpl}.
     */
    NotificationChannel channel();
}
