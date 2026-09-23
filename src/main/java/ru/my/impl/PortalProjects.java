package ru.my.impl;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Проекты, отмеченные администратором на вкладке «Проекты» — настройка {@value #KEY}.
 * <p>
 * Хранится как ключи проектов через запятую. К этому списку обращаются действия
 * с областью {@code selected}; действия с областью {@code all} его не смотрят.
 */
public final class PortalProjects {

    public static final String KEY = "sd.projects";

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
}
