package ru.my.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Закрывающие статусы по проектам — настройка {@value #KEY}.
 * <p>
 * Хранится одной строкой вида {@code "HELP:10001,3;SUP:10002"}: ключ проекта,
 * двоеточие, id статусов через запятую, проекты через точку с запятой. Отдельный
 * ключ настройки на каждый проект не заводится — их список заранее неизвестен.
 */
public final class ClosingStatuses {

    public static final String KEY = "closed.statuses";

    private ClosingStatuses() {
    }

    /**
     * Разбирает строку настройки. Некорректные куски пропускаются — строку
     * пишет только админ-страница, ломать рассылку из-за мусора не за что.
     *
     * @param raw значение настройки; может быть null или пустым
     * @return ключ проекта → id закрывающих статусов
     */
    public static Map<String, Set<String>> parse(String raw) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String chunk : raw.split(";")) {
            int colon = chunk.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String projectKey = chunk.substring(0, colon).trim();
            Set<String> statusIds = new LinkedHashSet<>();
            for (String id : chunk.substring(colon + 1).split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    statusIds.add(trimmed);
                }
            }
            if (!projectKey.isEmpty() && !statusIds.isEmpty()) {
                result.put(projectKey, statusIds);
            }
        }
        return result;
    }

    /**
     * Считается ли переход в статус закрывающим для проекта.
     *
     * @param raw            значение настройки {@value #KEY}
     * @param projectKey     ключ проекта задачи
     * @param statusId       id нового статуса
     * @param doneByCategory относится ли новый статус к категории «Done»
     * @return {@code true} если статус выбран для проекта администратором,
     *         а при отсутствии настройки для проекта — по категории статуса
     */
    public static boolean isClosing(String raw, String projectKey, String statusId, boolean doneByCategory) {
        Set<String> configured = parse(raw).get(projectKey);
        if (configured == null || configured.isEmpty()) {
            return doneByCategory;
        }
        return configured.contains(statusId);
    }
}
