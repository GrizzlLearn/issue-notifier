package ru.my.model;

import java.util.Locale;

/** Область действия: где именно работает уведомление о действии в задаче. */
public enum ActionScope {

    /** Во всех проектах инстанса. */
    ALL,

    /** Только в проектах, отмеченных администратором на вкладке «Проекты». */
    SELECTED,

    /** Только в проектах Service Desk — список вести не нужно, тип проекта известен Jira. */
    SERVICE_DESK;

    /** Значение, в котором область хранится в настройках: {@code "all"}, {@code "selected"}, {@code "service_desk"}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param key значение из настроек
     * @return область или {@code null}, если значение неизвестно
     */
    public static ActionScope byKey(String key) {
        for (ActionScope scope : values()) {
            if (scope.key().equals(key)) {
                return scope;
            }
        }
        return null;
    }
}
