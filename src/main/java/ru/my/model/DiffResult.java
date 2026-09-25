package ru.my.model;

import java.util.List;

/**
 * Результат разбора изменений в задаче Jira: список изменённых полей с их
 * старыми и новыми значениями. Не зависит от канала доставки — форматирование
 * под конкретный канал делает {@code MessageFormatter}.
 */
public class DiffResult {

    private final List<FieldChange> changes;

    /**
     * @param changes список изменённых полей; копируется, исходный список не изменяется
     */
    public DiffResult(List<FieldChange> changes) {
        this.changes = List.copyOf(changes);
    }

    /** Список изменённых полей. */
    public List<FieldChange> getChanges() {
        return changes;
    }

    /** {@code true} если ни одно поле не изменилось — уведомление отправлять не нужно. */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * Одно изменённое поле задачи.
     * <p>
     * Кроме отображаемых значений хранится {@link #toId()} — сырое {@code newvalue}
     * из changelog: для исполнителя это ключ пользователя, для статуса — id статуса.
     * Логика действий должна смотреть именно туда, а не в текущее состояние задачи:
     * между событием и рассылкой задачу могли переназначить ещё раз.
     */
    public static final class FieldChange {
        private final String fieldName;
        private final String fromValue; // null — поле было пустым
        private final String toValue;   // null — поле очищено
        private final String toId;      // newvalue из changelog; null — поле очищено
        private final boolean custom;   // fieldtype=custom в changelog

        public FieldChange(String fieldName, String fromValue, String toValue) {
            this(fieldName, fromValue, toValue, null);
        }

        public FieldChange(String fieldName, String fromValue, String toValue, String toId) {
            this(fieldName, fromValue, toValue, toId, false);
        }

        public FieldChange(String fieldName, String fromValue, String toValue, String toId, boolean custom) {
            this.fieldName = fieldName;
            this.fromValue = fromValue;
            this.toValue = toValue;
            this.toId = toId;
            this.custom = custom;
        }

        public String fieldName()  { return fieldName; }
        public String fromValue()  { return fromValue; }
        public String toValue()    { return toValue; }

        /** Идентификатор нового значения из changelog; {@code null} — поле очищено. */
        public String toId()       { return toId; }

        /**
         * {@code true} — это кастомное поле ({@code fieldtype=custom} в changelog).
         * По нему {@link WatchedFields} отличает кастомные поля от системных.
         */
        public boolean isCustom()  { return custom; }
    }
}
