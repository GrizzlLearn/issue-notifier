package ru.my.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Какие изменения полей задачи попадают в уведомление наблюдателям —
 * настройка вида {@code "description,custom,other"}.
 * <p>
 * Групп три, а не одна галка на поле: сообщение об изменении собирается из
 * changelog, где системных полей десятки, а кастомные у каждой инсталляции свои.
 * Снятая группа вычёркивает строки из того же сообщения; если после фильтра
 * не осталось ничего — уведомление не отправляется.
 */
public final class WatchedFields {

    /** Ключ настройки в {@link ru.my.api.AdminSettingsService}. */
    public static final String KEY = "watchers.fields";

    public static final String DESCRIPTION = "description";
    public static final String CUSTOM = "custom";
    public static final String OTHER = "other";

    /**
     * Админ снял все галки. Отдельное значение нужно по той же причине, что и в
     * {@link ru.my.impl.IssueRecipients}: пустая настройка означает «все группы»,
     * иначе снятые галки и рассылка противоречили бы друг другу.
     */
    public static final String NONE = "none";

    /** Группы в порядке чекбоксов админ-страницы. */
    public static final List<String> GROUPS = List.of(DESCRIPTION, CUSTOM, OTHER);

    private WatchedFields() {
    }

    /**
     * Оставляет в изменениях только те поля, группы которых отмечены админом.
     *
     * @param raw значение настройки; пустое или {@code null} — все группы,
     *            как плагин вёл себя до появления галок
     * @return тот же {@link DiffResult}, если отфильтровывать нечего
     */
    public static DiffResult filter(String raw, DiffResult diff) {
        Set<String> groups = groups(raw);
        if (groups.containsAll(GROUPS)) {
            return diff;
        }
        List<DiffResult.FieldChange> kept = new ArrayList<>();
        for (DiffResult.FieldChange change : diff.getChanges()) {
            if (groups.contains(groupOf(change))) {
                kept.add(change);
            }
        }
        return kept.size() == diff.getChanges().size() ? diff : new DiffResult(kept);
    }

    /** Отмечена ли группа; пустая настройка — отмечены все. */
    public static boolean includes(String raw, String group) {
        return groups(raw).contains(group);
    }

    /**
     * Группа изменённого поля. Кастомное поле распознаётся по {@code fieldtype}
     * из changelog, а не по имени: имя кастомного поля задаёт администратор
     * и оно вполне может совпасть с системным.
     */
    static String groupOf(DiffResult.FieldChange change) {
        if (change.isCustom()) {
            return CUSTOM;
        }
        return DESCRIPTION.equals(change.fieldName()) ? DESCRIPTION : OTHER;
    }

    private static Set<String> groups(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.copyOf(GROUPS);
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String chunk : raw.split(",")) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty() && GROUPS.contains(trimmed)) {
                groups.add(trimmed);
            }
        }
        return groups;
    }
}
