package ru.my.impl.util;

import com.atlassian.activeobjects.external.ActiveObjects;
import net.java.ao.DBParam;
import net.java.ao.Entity;
import net.java.ao.Query;

import java.util.function.Consumer;

/** Сохраняет строку по уникальному ключу, включая конкурентное первое создание. */
public final class AoUpsert {
    private AoUpsert() {
    }

    public static <T extends Entity> void save(ActiveObjects ao, Class<T> type,
                                              String column, String key, Consumer<T> update) {
        try {
            ao.executeInTransaction(() -> {
                T[] rows = ao.find(type, Query.select().where(column + " = ?", key));
                T entity;
                if (rows.length > 0) {
                    entity = rows[0];
                } else {
                    try {
                        entity = ao.create(type, new DBParam(column, key));
                    } catch (RuntimeException e) {
                        throw new CreateFailed(e);
                    }
                }
                update.accept(entity);
                entity.save();
                return null;
            });
        } catch (CreateFailed failure) {
            // После rollback читаем заново: другая нода могла создать строку первой.
            ao.executeInTransaction(() -> {
                T[] rows = ao.find(type, Query.select().where(column + " = ?", key));
                if (rows.length == 0) {
                    throw (RuntimeException) failure.getCause();
                }
                update.accept(rows[0]);
                rows[0].save();
                return null;
            });
        }
    }

    private static final class CreateFailed extends RuntimeException {
        private CreateFailed(RuntimeException cause) {
            super(cause);
        }
    }
}
