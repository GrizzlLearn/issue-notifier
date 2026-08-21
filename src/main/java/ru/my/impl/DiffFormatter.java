package ru.my.impl;

import org.ofbiz.core.entity.GenericEntityException;
import org.ofbiz.core.entity.GenericValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.model.DiffResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Преобразует changelog из события Jira в нейтральный {@link DiffResult}.
 * Статический утилитный класс — не является Spring-бином.
 * <p>
 * Работает с полями {@code field}, {@code oldstring}, {@code newstring} из
 * дочерних записей {@code ChildChangeItem} в таблице change-log Jira.
 */
public final class DiffFormatter {

    private static final Logger log = LoggerFactory.getLogger(DiffFormatter.class);

    private DiffFormatter() {
    }

    /**
     * Разбирает changelog события и возвращает список изменённых полей.
     * Если changelog отсутствует или произошла ошибка — возвращает пустой {@link DiffResult}.
     *
     * @param changeLog объект changelog из {@code IssueEvent.getChangeLog()}, может быть null
     * @return набор изменений, никогда не null
     */
    public static DiffResult parse(GenericValue changeLog) {
        if (changeLog == null) {
            return new DiffResult(List.of());
        }
        try {
            List<GenericValue> items = changeLog.getRelated("ChildChangeItem");
            return parseItems(items);
        } catch (GenericEntityException e) {
            log.warn("Не удалось прочитать ChildChangeItem из changelog: {}", e.getMessage());
            return new DiffResult(List.of());
        }
    }

    /**
     * Парсит отдельные записи ChildChangeItem.
     * Вынесен в отдельный метод для unit-тестирования без GenericValue.
     *
     * @param items записи ChildChangeItem
     * @return набор изменений, никогда не null
     */
    public static DiffResult parseItems(List<GenericValue> items) {
        List<DiffResult.FieldChange> changes = new ArrayList<>();
        for (GenericValue item : items) {
            String field = item.getString("field");
            if (field == null || field.isBlank()) {
                continue;
            }
            String oldString = item.getString("oldstring");
            String newString = item.getString("newstring");
            changes.add(new DiffResult.FieldChange(field, oldString, newString));
        }
        return new DiffResult(changes);
    }
}
