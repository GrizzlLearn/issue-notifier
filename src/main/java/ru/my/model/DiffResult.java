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
     *
     * @param fieldName  отображаемое название поля, например {@code "Assignee"}
     * @param fromValue  значение до изменения; {@code null} — поле было пустым
     * @param toValue    значение после изменения; {@code null} — поле очищено
     */
    public record FieldChange(String fieldName, String fromValue, String toValue) {}
}
