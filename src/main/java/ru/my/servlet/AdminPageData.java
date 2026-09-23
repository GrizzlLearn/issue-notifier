package ru.my.servlet;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.type.ProjectTypeKey;
import ru.my.impl.ActionTemplates;
import ru.my.impl.util.JsonUtil;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/**
 * Справочники для админ-страницы (проекты, статусы, каталог действий), собранные
 * в JSON прямо при рендере страницы.
 * <p>
 * REST для них не нужен: страницу отдаёт наш же сервлет внутри Jira, а данные
 * лежат в тех же процессах — проще положить их в HTML, чем ходить за ними из
 * браузера. Поиск проектов в пикере тоже идёт по этому массиву, без запросов
 * на каждое нажатие клавиши.
 */
public final class AdminPageData {

    /** Тип проекта, который создаёт плагин Jira Service Desk. */
    static final ProjectTypeKey SERVICE_DESK = new ProjectTypeKey("service_desk");

    /** Каналы, по которым рассылаются уведомления о действиях (email не участвует). */
    private static final List<NotificationChannel> ACTION_CHANNELS =
            List.of(NotificationChannel.MATTERMOST, NotificationChannel.TELEGRAM);

    private AdminPageData() {
    }

    /**
     * Собирает JSON-объект со справочниками страницы.
     *
     * @param projects проекты инстанса
     * @param statuses статусы инстанса
     * @return строка JSON, пригодная для вставки в {@code <script>} — см. {@link #embed(String)}
     */
    public static String toJson(Collection<Project> projects, Collection<Status> statuses) {
        StringJoiner projectsJson = new StringJoiner(",", "[", "]");
        projects.stream()
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> projectsJson.add("{"
                        + "\"value\":" + JsonUtil.jsonString(p.getKey())
                        + ",\"label\":" + JsonUtil.jsonString(p.getName() + " (" + p.getKey() + ")")
                        + ",\"serviceDesk\":" + SERVICE_DESK.equals(p.getProjectTypeKey())
                        + "}"));

        StringJoiner statusesJson = new StringJoiner(",", "[", "]");
        statuses.stream()
                .sorted(Comparator.comparing(Status::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(s -> statusesJson.add("{"
                        + "\"value\":" + JsonUtil.jsonString(s.getId())
                        + ",\"label\":" + JsonUtil.jsonString(s.getName())
                        + ",\"done\":" + (s.getStatusCategory() != null
                                && "done".equals(s.getStatusCategory().getKey()))
                        + "}"));

        StringJoiner actionsJson = new StringJoiner(",", "[", "]");
        for (NotificationAction action : NotificationAction.values()) {
            StringJoiner placeholders = new StringJoiner(",", "[", "]");
            action.placeholders().forEach(ph -> placeholders.add(JsonUtil.jsonString(ph)));

            StringJoiner channels = new StringJoiner(",", "[", "]");
            for (NotificationChannel channel : ACTION_CHANNELS) {
                channels.add("{"
                        + "\"channel\":" + JsonUtil.jsonString(channel.name())
                        + ",\"templateKey\":" + JsonUtil.jsonString(ActionTemplates.templateKey(action, channel))
                        + "}");
            }

            actionsJson.add("{"
                    + "\"key\":" + JsonUtil.jsonString(action.key())
                    + ",\"title\":" + JsonUtil.jsonString(action.title())
                    + ",\"enabledKey\":" + JsonUtil.jsonString(ActionTemplates.enabledKey(action))
                    + ",\"scopeKey\":" + JsonUtil.jsonString(ActionTemplates.scopeKey(action))
                    + ",\"defaultScope\":" + JsonUtil.jsonString(action.defaultScope().key())
                    + ",\"scopeFixed\":" + action.isScopeFixed()
                    + ",\"placeholders\":" + placeholders
                    + ",\"channels\":" + channels
                    + "}");
        }

        return "{\"projects\":" + projectsJson
                + ",\"statuses\":" + statusesJson
                + ",\"actions\":" + actionsJson + "}";
    }

    /**
     * Оборачивает JSON в {@code <script>}-тег.
     * <p>
     * Последовательность {@code </} экранируется: иначе название проекта вида
     * {@code </script>} закрыло бы тег и превратилось в разметку страницы.
     */
    public static String embed(String json) {
        return "<script>window.ISSUE_NOTIFIER_DATA = "
                + json.replace("</", "<\\/")
                + ";</script>";
    }
}
