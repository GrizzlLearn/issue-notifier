package ru.my.impl.util;

import ru.my.model.DiffResult;

import java.util.List;

/**
 * Делит изменения на "короткие" (влезают в таблицу) и "длинные" (нужен diff-блок)
 * по суммарной длине {@code fromValue}+{@code toValue}. Общая логика для форматтеров,
 * которые рендерят изменения по-разному в зависимости от размера значения
 * (Mattermost, Telegram).
 */
public final class ChangeSplitter {

    private ChangeSplitter() {}

    public static List<DiffResult.FieldChange> shortChanges(List<DiffResult.FieldChange> changes, int threshold) {
        return changes.stream().filter(c -> length(c) <= threshold).toList();
    }

    public static List<DiffResult.FieldChange> longChanges(List<DiffResult.FieldChange> changes, int threshold) {
        return changes.stream().filter(c -> length(c) > threshold).toList();
    }

    private static int length(DiffResult.FieldChange c) {
        return len(c.fromValue()) + len(c.toValue());
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
