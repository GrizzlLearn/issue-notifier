package ru.my.model;

import java.util.List;

/**
 * Действие в задаче, на которое плагин шлёт уведомление по шаблону из настроек.
 * <p>
 * Текст задаёт администратор на вкладке «Действия»: ключи
 * {@code action.<key>.enabled} и {@code action.<key>.template.<channel>}.
 * Встроенных текстов нет — пустой шаблон означает, что по этому каналу
 * уведомление не отправляется. Шаблоны поддерживаются для Mattermost
 * и Telegram; по email уведомления о действиях не рассылаются.
 */
public enum NotificationAction {

    MENTION("mention", "Упоминание через @",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "comment")),

    CLOSED("closed", "Переход в закрывающий статус",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "status")),

    COMMENT_ADDED("commentAdded", "Новый комментарий",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "comment"));

    private final String key;
    private final String title;
    private final List<String> placeholders;

    NotificationAction(String key, String title, List<String> placeholders) {
        this.key = key;
        this.title = title;
        this.placeholders = List.copyOf(placeholders);
    }

    /** Идентификатор действия в ключах настроек, например {@code "mention"}. */
    public String key() {
        return key;
    }

    /** Человекочитаемое название для админ-страницы. */
    public String title() {
        return title;
    }

    /** Плейсхолдеры, допустимые в шаблоне этого действия (без фигурных скобок). */
    public List<String> placeholders() {
        return placeholders;
    }

    /** Находит действие по {@link #key()}; {@code null} — неизвестный ключ. */
    public static NotificationAction byKey(String key) {
        for (NotificationAction action : values()) {
            if (action.key.equals(key)) {
                return action;
            }
        }
        return null;
    }
}
