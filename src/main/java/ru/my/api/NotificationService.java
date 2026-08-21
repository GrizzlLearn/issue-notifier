package ru.my.api;

import com.atlassian.jira.event.issue.IssueEvent;

/**
 * Центральный сервис обработки событий Jira и рассылки уведомлений наблюдателям.
 * Вызывается асинхронно из {@link ru.my.impl.IssueEventListener}.
 */
public interface NotificationService {

    /**
     * Обрабатывает событие изменения задачи: формирует diff, находит наблюдателей,
     * применяет фильтры и делегирование, отправляет уведомления по выбранным каналам.
     * <p>
     * Если в событии нет изменённых полей — выходит досрочно без обращения к БД.
     *
     * @param event событие Jira с информацией об изменении задачи
     */
    void processEvent(IssueEvent event);
}
