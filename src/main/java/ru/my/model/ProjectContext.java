package ru.my.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Контекст проектов: набор проектов и категорий плюс свой набор включённых
 * действий и получателей. Администратор ведёт контексты на вкладке
 * «Контекст проектов», а на вкладке «Действия» настраивает выбранный контекст.
 * <p>
 * Тексты уведомлений в контекст не входят — шаблоны остаются едиными на инстанс
 * (см. {@link ActionTemplates#templateKey}). Контекст решает только, включено ли
 * действие и кто его получатели.
 * <p>
 * Объект неизменяемый; читается и пишется через {@link ProjectContexts}.
 */
public final class ProjectContext {

    /** Настройка одного действия внутри контекста. */
    public static final class ActionSetting {

        private final boolean enabled;
        private final String recipients;

        /**
         * @param enabled    уведомлять ли об этом действии в этом контексте
         * @param recipients получатели через запятую в формате
         *                   {@link ru.my.impl.IssueRecipients}; пустая строка — по умолчанию
         */
        public ActionSetting(boolean enabled, String recipients) {
            this.enabled = enabled;
            this.recipients = recipients == null ? "" : recipients;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String recipients() {
            return recipients;
        }
    }

    private final String id;
    private final String name;
    private final Set<String> projects;
    private final Set<String> categories;
    private final Map<String, ActionSetting> actions;
    private final Map<String, Set<String>> closedStatuses;

    /**
     * @param id             идентификатор контекста или {@link ProjectContexts#DEFAULT_ID}
     * @param name           название для вкладки админки
     * @param projects       ключи проектов, отмеченных явно
     * @param categories     id категорий проектов
     * @param actions        ключ действия ({@link NotificationAction#key()}) → настройка
     * @param closedStatuses ключ проекта → id закрывающих статусов
     */
    public ProjectContext(String id, String name, Set<String> projects, Set<String> categories,
                          Map<String, ActionSetting> actions, Map<String, Set<String>> closedStatuses) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.projects = projects == null ? Set.of() : new LinkedHashSet<>(projects);
        this.categories = categories == null ? Set.of() : new LinkedHashSet<>(categories);
        this.actions = actions == null ? Map.of() : new LinkedHashMap<>(actions);
        this.closedStatuses = closedStatuses == null ? Map.of() : new LinkedHashMap<>(closedStatuses);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Set<String> projects() {
        return Set.copyOf(projects);
    }

    public Set<String> categories() {
        return Set.copyOf(categories);
    }

    /** Ключи действий, у которых есть запись в этом контексте. */
    public Set<String> actionKeys() {
        return Set.copyOf(actions.keySet());
    }

    /** {@code true} — встроенный контекст «Остальные проекты». */
    public boolean isDefault() {
        return ProjectContexts.DEFAULT_ID.equals(id);
    }

    /**
     * Уведомлять ли об этом действии. Записи нет — не уведомляем: свежая
     * установка должна молчать, пока администратор не включил действие сам.
     */
    public boolean isEnabled(NotificationAction action) {
        ActionSetting setting = actions.get(action.key());
        return setting != null && setting.isEnabled();
    }

    /**
     * Получатели действия в формате {@link ru.my.impl.IssueRecipients};
     * пустая строка — записи нет, получателей определяет вызывающий код.
     */
    public String recipients(NotificationAction action) {
        ActionSetting setting = actions.get(action.key());
        return setting == null ? "" : setting.recipients();
    }

    /** Настройка действия или {@code null} — нужна только сериализации и REST. */
    public ActionSetting action(NotificationAction action) {
        return actions.get(action.key());
    }

    /** id закрывающих статусов проекта в этом контексте; пусто — не заданы. */
    public Set<String> closingStatuses(String projectKey) {
        Set<String> statuses = closedStatuses.get(projectKey);
        return statuses == null ? Set.of() : Set.copyOf(statuses);
    }

    /** Все закрывающие статусы контекста — нужно сериализации и админ-странице. */
    public Map<String, Set<String>> closedStatuses() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        closedStatuses.forEach((project, statuses) -> copy.put(project, Set.copyOf(statuses)));
        return copy;
    }

    /**
     * Считается ли переход в статус закрывающим для проекта в этом контексте.
     * Правила по категории статуса нет намеренно: в разных workflow закрытие
     * называется по-разному, поэтому закрывающим считается только выбранный статус.
     */
    public boolean isClosing(String projectKey, String statusId) {
        return closingStatuses(projectKey).contains(statusId);
    }

    /** Отмечен ли проект в контексте явно (не через категорию). */
    public boolean hasProject(String projectKey) {
        return projectKey != null && projects.contains(projectKey);
    }

    /** Отмечена ли категория проекта в контексте. */
    public boolean hasCategory(Long categoryId) {
        return categoryId != null && categories.contains(String.valueOf(categoryId));
    }
}
