package ru.my.api;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.DiffResult;

/**
 * Центральный сервис рассылки уведомлений наблюдателям изменённой задачи.
 * Вызывается из {@link ru.my.impl.IssueEventListener} уже после парсинга diff:
 * changelog читается в потоке Jira-события, а не в рабочем потоке пула.
 */
public interface NotificationService {

    /**
     * Обрабатывает изменение задачи: находит наблюдателей, применяет фильтры
     * и делегирование, отправляет уведомления по выбранным каналам.
     * <p>
     * Если {@code diff} пуст — выходит досрочно без обращения к БД.
     *
     * @param issue  изменённая задача
     * @param author пользователь, инициировавший изменение; может быть null
     * @param diff   распарсенный набор изменений
     */
    void processEvent(Issue issue, ApplicationUser author, DiffResult diff);
}
