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
            List.of("issueKey", "issueUrl", "summary", "project", "author", "comment"),
            ActionScope.ALL, false, false),

    /**
     * Область не настраивается: закрывающие статусы задаются на каждый проект,
     * поэтому действие работает ровно там, где они выбраны.
     */
    CLOSED("closed", "Переход в закрывающий статус",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "status"),
            ActionScope.SELECTED, true, true),

    /** Получатель — тот, кого назначили; список приходит от слушателя событий. */
    ASSIGNED("assigned", "Назначение исполнителем",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "assignee"),
            ActionScope.ALL, false, false),

    COMMENT_ADDED("commentAdded", "Новый комментарий",
            List.of("issueKey", "issueUrl", "summary", "project", "author", "comment"),
            ActionScope.SELECTED, false, true);

    private final String key;
    private final String title;
    private final List<String> placeholders;
    private final ActionScope defaultScope;
    private final boolean scopeFixed;
    private final boolean recipientsConfigurable;

    NotificationAction(String key, String title, List<String> placeholders,
                       ActionScope defaultScope, boolean scopeFixed,
                       boolean recipientsConfigurable) {
        this.key = key;
        this.title = title;
        this.placeholders = List.copyOf(placeholders);
        this.defaultScope = defaultScope;
        this.scopeFixed = scopeFixed;
        this.recipientsConfigurable = recipientsConfigurable;
    }

    /** Идентификатор действия в ключах настроек, например {@code "mention"}. */
    public String key() {
        return key;
    }

    /** Человекочитаемое название для админ-страницы. */
    public String title() {
        return title;
    }

    /**
     * Область по умолчанию, пока администратор её не менял:
     * упоминание касается человека лично и работает везде, портальные действия —
     * только в отмеченных проектах.
     */
    public ActionScope defaultScope() {
        return defaultScope;
    }

    /** {@code true} — область задана самим действием, администратор её не переключает. */
    public boolean isScopeFixed() {
        return scopeFixed;
    }

    /**
     * {@code true} — администратор выбирает получателей полями задачи
     * (см. {@link ru.my.impl.IssueRecipients}); {@code false} — уведомление
     * уходит наблюдателям или явному списку от вызывающего кода.
     */
    public boolean isRecipientsConfigurable() {
        return recipientsConfigurable;
    }

    /**
     * {@code true} — в шаблоне действия есть текст комментария, поэтому у него
     * два шаблона на канал: обычный и на случай, когда текст отправлять нельзя.
     */
    public boolean carriesCommentText() {
        return placeholders.contains("comment");
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
