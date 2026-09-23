package ru.my.api;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;

import java.util.List;
import java.util.Map;

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

    /**
     * Рассылает уведомление о действии в задаче по шаблону из настроек плагина.
     * Если действие выключено администратором — ничего не делает.
     * <p>
     * Фильтры получателей те же, что и для изменения полей: неактивные, автор
     * действия, отключившие уведомления и не следящие за проектом отсеиваются,
     * затем применяется делегирование и дедупликация.
     *
     * @param issue        задача, в которой произошло действие
     * @param author       инициатор действия; может быть null
     * @param action       действие — определяет шаблон и флаг включённости
     * @param recipients   явные получатели (например, упомянутые пользователи);
     *                     пустой список или null — рассылка наблюдателям задачи
     * @param placeholders значения плейсхолдеров шаблона без фигурных скобок
     */
    void processAction(Issue issue, ApplicationUser author, NotificationAction action,
                       List<ApplicationUser> recipients, Map<String, String> placeholders);
}
