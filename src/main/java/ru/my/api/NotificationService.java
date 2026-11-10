package ru.my.api;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.CommentScope;
import ru.my.model.DeliveryProgress;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Центральный сервис рассылки уведомлений наблюдателям изменённой задачи.
 * Вызывается из {@link ru.my.impl.IssueEventListener} уже после парсинга diff:
 * changelog читается в потоке Jira-события, а не в рабочем потоке пула.
 * Сбои отдельных доставок не останавливают остальные, но после обхода получателей
 * метод бросает исключение: событие остаётся в очереди для повторной попытки.
 * Подтверждённые пары получатель/канал пропускаются при повторе. Падение после
 * отправки, но до фиксации подтверждения всё ещё может дать дубликат.
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
    default void processEvent(Issue issue, ApplicationUser author, DiffResult diff) {
        processEvent(issue, author, diff, List.of());
    }

    /**
     * То же, но без тех, кому по этому же событию уже ушло уведомление
     * о действии в текущей или предыдущей попытке обработки.
     *
     * @param exclude получатели, которых нужно пропустить
     */
    default void processEvent(Issue issue, ApplicationUser author, DiffResult diff,
                              Collection<ApplicationUser> exclude) {
        processEvent(issue, author, diff, exclude, new DeliveryProgress());
    }

    /** Общий прогресс доставок события сохраняется очередью между попытками. */
    void processEvent(Issue issue, ApplicationUser author, DiffResult diff,
                      Collection<ApplicationUser> exclude, DeliveryProgress progress);

    /**
     * Рассылает уведомление о действии в задаче по шаблону из настроек плагина.
     * Если действие выключено администратором — ничего не делает.
     * <p>
     * Неактивные, автор действия и отключившие уведомления отсеиваются;
     * затем применяется делегирование и дедупликация. Пользовательский фильтр
     * проектов применяется только к изменениям полей.
     *
     * @param issue        задача, в которой произошло действие
     * @param author       инициатор действия; может быть null
     * @param action       действие — определяет шаблон и флаг включённости
     * @param recipients   явные получатели (например, упомянутые пользователи);
     *                     пустой список или null — получатели из контекста действия
     *                     (по умолчанию наблюдатели)
     * @param placeholders значения плейсхолдеров шаблона без фигурных скобок
     * @return те, кому сообщение действительно ушло — пустой список, если действие
     *         выключено, вне области, без шаблонов или без подходящих получателей
     */
    default List<ApplicationUser> processAction(Issue issue, ApplicationUser author, NotificationAction action,
                                                List<ApplicationUser> recipients, Map<String, String> placeholders) {
        return processAction(issue, author, action, recipients, placeholders, List.of());
    }

    /**
     * То же, но без тех, кому по этому же событию уже ушло другое уведомление:
     * упомянутый в комментарии не должен получить второе сообщение как исполнитель.
     *
     * @param exclude получатели, которых нужно пропустить
     */
    default List<ApplicationUser> processAction(Issue issue, ApplicationUser author, NotificationAction action,
                                                List<ApplicationUser> recipients, Map<String, String> placeholders,
                                                Collection<ApplicationUser> exclude) {
        return processAction(issue, author, action, recipients, placeholders, exclude, CommentScope.NONE);
    }

    /**
     * То же, но с ограничениями комментария, из-за которого идёт рассылка.
     * Каждый получатель проверяется на право видеть этот комментарий, а по
     * внутреннему комментарию Service Desk не применяется делегирование.
     *
     * @param scope ограничения комментария; {@link CommentScope#NONE} — событие
     *              не про комментарий
     */
    default List<ApplicationUser> processAction(Issue issue, ApplicationUser author, NotificationAction action,
                                                List<ApplicationUser> recipients, Map<String, String> placeholders,
                                                Collection<ApplicationUser> exclude, CommentScope scope) {
        return processAction(issue, author, action, recipients, placeholders, exclude, scope, new DeliveryProgress());
    }

    /** Общий прогресс учитывает успешные доставки предыдущих попыток события. */
    List<ApplicationUser> processAction(Issue issue, ApplicationUser author, NotificationAction action,
                                        List<ApplicationUser> recipients, Map<String, String> placeholders,
                                        Collection<ApplicationUser> exclude, CommentScope scope, DeliveryProgress progress);
}
