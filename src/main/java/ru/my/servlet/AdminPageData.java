package ru.my.servlet;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectCategory;
import com.atlassian.jira.project.type.ProjectTypeKey;
import ru.my.model.ActionTemplates;
import ru.my.model.JsonUtil;
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

    /**
     * Тип кастомного поля с пользователем. Проверяем вхождение, а не полное совпадение:
     * ключи вида {@code ...:userpicker}, {@code ...:multiuserpicker} и searcher-варианты
     * все содержат эту подстроку, а перечислять их поимённо пришлось бы поддерживать.
     */
    static final String USER_PICKER_TYPE = "userpicker";

    private AdminPageData() {
    }

    /**
     * Собирает JSON-объект со справочниками страницы.
     *
     * @param projects проекты инстанса
     * @param statuses статусы инстанса
     * @return строка JSON, пригодная для вставки в {@code <script>} — см. {@link #embed(String)}
     */
    public static String toJson(Collection<Project> projects, Collection<Status> statuses,
                                Collection<CustomField> customFields,
                                Collection<ProjectCategory> categories) {
        StringJoiner projectsJson = new StringJoiner(",", "[", "]");
        projects.stream()
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> projectsJson.add("{"
                        + "\"value\":" + JsonUtil.jsonString(p.getKey())
                        + ",\"label\":" + JsonUtil.jsonString(p.getName() + " (" + p.getKey() + ")")
                        + ",\"serviceDesk\":" + SERVICE_DESK.equals(p.getProjectTypeKey())
                        + ",\"category\":" + (p.getProjectCategoryObject() != null
                                ? JsonUtil.jsonString(String.valueOf(p.getProjectCategoryObject().getId()))
                                : "null")
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
            for (NotificationChannel channel : NotificationChannel.actionChannels()) {
                channels.add("{"
                        + "\"channel\":" + JsonUtil.jsonString(channel.name())
                        + ",\"templateKey\":" + JsonUtil.jsonString(ActionTemplates.templateKey(action, channel))
                        + ",\"templateKeyNoText\":" + (action.carriesCommentText()
                                ? JsonUtil.jsonString(ActionTemplates.templateKeyNoText(action, channel))
                                : "null")
                        + "}");
            }

            actionsJson.add("{"
                    + "\"recipientsKey\":" + (action.isRecipientsConfigurable()
                            ? JsonUtil.jsonString(ActionTemplates.recipientsKey(action)) : "null")
                    + ",\"key\":" + JsonUtil.jsonString(action.key())
                    + ",\"title\":" + JsonUtil.jsonString(action.title())
                    + ",\"enabledKey\":" + JsonUtil.jsonString(ActionTemplates.enabledKey(action))
                    + ",\"scopeKey\":" + JsonUtil.jsonString(ActionTemplates.scopeKey(action))
                    + ",\"defaultScope\":" + JsonUtil.jsonString(action.defaultScope().key())
                    + ",\"scopeFixed\":" + action.isScopeFixed()
                    + ",\"placeholders\":" + placeholders
                    + ",\"channels\":" + channels
                    + "}");
        }

        StringJoiner userFieldsJson = new StringJoiner(",", "[", "]");
        customFields.stream()
                .filter(cf -> cf.getCustomFieldType() != null
                        && cf.getCustomFieldType().getKey().contains(USER_PICKER_TYPE))
                .sorted(Comparator.comparing(CustomField::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(cf -> userFieldsJson.add("{"
                        + "\"value\":" + JsonUtil.jsonString(cf.getId())
                        + ",\"label\":" + JsonUtil.jsonString(cf.getName())
                        + ",\"scope\":" + JsonUtil.jsonString(fieldScope(cf))
                        + "}"));

        StringJoiner categoriesJson = new StringJoiner(",", "[", "]");
        categories.stream()
                .sorted(Comparator.comparing(ProjectCategory::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(c -> categoriesJson.add("{"
                        + "\"value\":" + JsonUtil.jsonString(String.valueOf(c.getId()))
                        + ",\"label\":" + JsonUtil.jsonString(c.getName())
                        + "}"));

        return "{\"projects\":" + projectsJson
                + ",\"categories\":" + categoriesJson
                + ",\"statuses\":" + statusesJson
                + ",\"userFields\":" + userFieldsJson
                + ",\"actions\":" + actionsJson + "}";
    }

    /**
     * Где поле доступно: одноимённые поля из разных схем — это разные id,
     * поэтому без области их в списке не различить. Пустой список связанных
     * проектов означает глобальный контекст.
     */
    private static String fieldScope(CustomField field) {
        List<Project> associated = field.getAssociatedProjectObjects();
        if (associated.isEmpty()) {
            return "все проекты";
        }
        StringJoiner keys = new StringJoiner(", ");
        associated.forEach(p -> keys.add(p.getKey()));
        return keys.toString();
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
