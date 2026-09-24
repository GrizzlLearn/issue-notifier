package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.user.ApplicationUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Получатели уведомления о действии — настройка вида
 * {@code "reporter,assignee,customfield_10100"}.
 * <p>
 * Источник — либо встроенное поле задачи, либо id кастомного поля с пользователем.
 * Разбивки по проектам и типам задач нет намеренно: поле, которого нет в схеме
 * экрана конкретной задачи, просто не даёт получателя, поэтому один плоский
 * список работает для разных workflow.
 */
public final class IssueRecipients {

    public static final String REPORTER = "reporter";
    public static final String CREATOR  = "creator";
    public static final String ASSIGNEE = "assignee";
    public static final String WATCHERS = "watchers";

    /**
     * Админ снял все галки. Отдельное значение нужно потому, что пустая настройка
     * означает наблюдателей — иначе снятые галки и рассылка наблюдателям
     * противоречили бы друг другу.
     */
    public static final String NONE = "none";

    /** Встроенные источники — те же значения используются чекбоксами админ-страницы. */
    public static final List<String> BUILT_IN = List.of(REPORTER, CREATOR, ASSIGNEE, WATCHERS);

    private IssueRecipients() {
    }

    /**
     * Разворачивает настройку в список получателей.
     * <p>
     * Пустая настройка — это наблюдатели: так действие вело себя до появления
     * выбора, и апгрейд плагина не должен молча отключать рассылку.
     * Дубли убираются здесь, потому что один и тот же человек легко оказывается
     * и автором, и исполнителем.
     *
     * @param raw значение настройки; пустое или {@code null} — наблюдатели
     * @return получатели в порядке источников; пустой список — отправлять некому
     */
    public static List<ApplicationUser> resolve(String raw, Issue issue,
                                                WatcherManager watcherManager,
                                                CustomFieldManager customFieldManager) {
        Map<String, ApplicationUser> unique = new LinkedHashMap<>();

        for (String source : sources(raw)) {
            switch (source) {
                case REPORTER -> add(unique, issue.getReporter());
                case CREATOR  -> add(unique, issue.getCreator());
                case ASSIGNEE -> add(unique, issue.getAssignee());
                case WATCHERS -> watcherManager.getWatchers(issue, Locale.ROOT).forEach(w -> add(unique, w));
                case NONE -> { /* получатели не выбраны */ }
                default -> addCustomField(unique, issue, customFieldManager, source);
            }
        }
        return List.copyOf(unique.values());
    }

    /** Источники настройки; пустое значение — наблюдатели. */
    private static List<String> sources(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(WATCHERS);
        }
        List<String> sources = new ArrayList<>();
        for (String chunk : raw.split(",")) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                sources.add(trimmed);
            }
        }
        return sources;
    }

    /**
     * Значение кастомного поля: user picker отдаёт одного пользователя,
     * multi user picker — коллекцию. Поле, которого нет в задаче, даёт {@code null}.
     */
    private static void addCustomField(Map<String, ApplicationUser> unique, Issue issue,
                                       CustomFieldManager customFieldManager, String fieldId) {
        CustomField field = customFieldManager.getCustomFieldObject(fieldId);
        if (field == null) {
            return;
        }
        Object value = issue.getCustomFieldValue(field);
        if (value instanceof ApplicationUser user) {
            add(unique, user);
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof ApplicationUser user) {
                    add(unique, user);
                }
            }
        }
    }

    private static void add(Map<String, ApplicationUser> unique, ApplicationUser user) {
        if (user != null) {
            unique.putIfAbsent(user.getKey(), user);
        }
    }
}
