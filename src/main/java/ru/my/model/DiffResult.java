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

    /** Одно изменённое поле задачи. */
    public static final class FieldChange {
        private final String fieldName;
        private final String fromValue; // null — поле было пустым
        private final String toValue;   // null — поле очищено

        public FieldChange(String fieldName, String fromValue, String toValue) {
            this.fieldName = fieldName;
            this.fromValue = fromValue;
            this.toValue = toValue;
        }

        public String fieldName()  { return fieldName; }
        public String fromValue()  { return fromValue; }
        public String toValue()    { return toValue; }
    }
}
