package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;
import ru.my.model.NotificationAction;
import ru.my.model.ProjectContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Контекст проектов для админ-страницы. Клиент присылает и получает структуру,
 * а не ключи настроек: имя, состав и настройки действий.
 * <p>
 * Mutable POJO с {@code @JsonProperty} на каждом поле — Jira отключает автодетект
 * геттеров в своём Jackson, а {@code record} ломает сканирование JAR-а Jersey
 * (см. {@link UserSettingsDto}).
 */
@SuppressWarnings("unused") // сеттеры/геттеры используются JAX-RS при сериализации
public class ProjectContextDto {

    @JsonProperty
    private String id;
    @JsonProperty
    private String name;
    @JsonProperty
    private List<String> projects;
    @JsonProperty
    private List<String> categories;
    /** Ключ действия → {@code {"enabled": true, "recipients": "reporter,assignee"}}. */
    @JsonProperty
    private Map<String, ActionDto> actions;
    /** Ключ проекта → id закрывающих статусов. */
    @JsonProperty
    private Map<String, List<String>> closedStatuses;

    /** Настройка одного действия в контексте. */
    @SuppressWarnings("unused") // как и снаружи: вызывает JAX-RS, не наш код
    public static class ActionDto {

        @JsonProperty
        private boolean enabled;
        @JsonProperty
        private String recipients;

        /** Нужен Jackson-у для разбора тела PUT. */
        public ActionDto() {
        }

        public ActionDto(boolean enabled, String recipients) {
            this.enabled = enabled;
            this.recipients = recipients;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRecipients() { return recipients; }
        public void setRecipients(String recipients) { this.recipients = recipients; }
    }

    public ProjectContextDto() {
    }

    /** Модель → DTO для GET. */
    public static ProjectContextDto from(ProjectContext context) {
        ProjectContextDto dto = new ProjectContextDto();
        dto.id = context.id();
        dto.name = context.name();
        dto.projects = new ArrayList<>(context.projects());
        dto.categories = new ArrayList<>(context.categories());

        dto.actions = new LinkedHashMap<>();
        for (NotificationAction action : NotificationAction.values()) {
            ProjectContext.ActionSetting setting = context.action(action);
            if (setting != null) {
                dto.actions.put(action.key(), new ActionDto(setting.isEnabled(), setting.recipients()));
            }
        }

        dto.closedStatuses = new LinkedHashMap<>();
        context.closedStatuses().forEach((project, ids) ->
                dto.closedStatuses.put(project, new ArrayList<>(ids)));
        return dto;
    }

    /**
     * DTO → модель. Неизвестные ключи действий отбрасывает уже
     * {@link ru.my.model.ProjectContexts}; здесь только приведение типов.
     */
    public ProjectContext toModel() {
        Map<String, ProjectContext.ActionSetting> settings = new LinkedHashMap<>();
        if (actions != null) {
            actions.forEach((key, value) -> {
                if (key != null && value != null) {
                    settings.put(key.trim(), new ProjectContext.ActionSetting(
                            value.isEnabled(), value.getRecipients()));
                }
            });
        }

        Map<String, Set<String>> statuses = new LinkedHashMap<>();
        if (closedStatuses != null) {
            closedStatuses.forEach((project, ids) -> {
                if (project != null && ids != null && !ids.isEmpty()) {
                    statuses.put(project.trim(), new LinkedHashSet<>(ids));
                }
            });
        }

        return new ProjectContext(id == null ? "" : id.trim(), name,
                projects == null ? Set.of() : new LinkedHashSet<>(projects),
                categories == null ? Set.of() : new LinkedHashSet<>(categories),
                settings, statuses);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getProjects() { return projects; }
    public void setProjects(List<String> projects) { this.projects = projects; }
    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> categories) { this.categories = categories; }
    public Map<String, ActionDto> getActions() { return actions; }
    public void setActions(Map<String, ActionDto> actions) { this.actions = actions; }
    public Map<String, List<String>> getClosedStatuses() { return closedStatuses; }
    public void setClosedStatuses(Map<String, List<String>> closedStatuses) { this.closedStatuses = closedStatuses; }
}
