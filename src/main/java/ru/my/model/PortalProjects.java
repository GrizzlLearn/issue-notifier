package ru.my.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Проекты, отмеченные администратором на вкладке «Проекты» — настройки
 * {@value #KEY} и {@value #CATEGORIES_KEY}.
 * <p>
 * Хранится как ключи проектов через запятую и id категорий через запятую.
 * Категории держим отдельным списком, а не разворачиваем в проекты при
 * сохранении: иначе проект, добавленный в категорию завтра, в область
 * не попал бы. К этим спискам обращаются действия с областью {@code selected};
 * действия с областью {@code all} их не смотрят.
 */
public final class PortalProjects {

    public static final String KEY = "sd.projects";

    public static final String CATEGORIES_KEY = "sd.categories";

    private PortalProjects() {
    }

    /**
     * @param raw значение настройки; может быть null или пустым
     * @return ключи проектов в порядке сохранения
     */
    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Входит ли проект в список отмеченных. Пустой список не содержит ничего. */
    public static boolean contains(String raw, String projectKey) {
        return projectKey != null && parse(raw).contains(projectKey);
    }

    /**
     * Входит ли проект в область: отмечен сам или отмечена его категория.
     *
     * @param rawProjects   значение настройки {@value #KEY}
     * @param rawCategories значение настройки {@value #CATEGORIES_KEY}
     * @param projectKey    ключ проекта задачи
     * @param categoryId    id категории проекта; {@code null} — проект вне категорий
     */
    public static boolean contains(String rawProjects, String rawCategories,
                                   String projectKey, Long categoryId) {
        return contains(rawProjects, projectKey)
                || (categoryId != null && parse(rawCategories).contains(String.valueOf(categoryId)));
    }
}
