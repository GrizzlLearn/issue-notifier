package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.java.ao.DBParam;
import net.java.ao.Query;
import ru.my.ao.AdminSettingsEntity;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.TimeUnit;

/**
 * Реализация {@link AdminSettingsService} на базе Active Objects.
 * Настройки хранятся в таблице {@code ADMIN_SETTINGS} в формате key-value.
 * Запись создаётся при первом вызове {@link #set}, до этого {@link #get}
 * возвращает переданное значение по умолчанию.
 * <p>
 * Результаты кешируются на 60 секунд — настройки меняются редко и только вручную.
 * {@link #set} инвалидирует запись для изменённого ключа.
 */
@Named
@ExportAsService(AdminSettingsService.class)
public class AdminSettingsServiceImpl implements AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsServiceImpl.class);

    /** Сентинел в кеше: ключ отсутствует в БД — вернуть defaultValue вызывающему. */
    private static final String ABSENT = "\0";

    private final ActiveObjects ao;
    private final Cache<String, String> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    @Inject
    public AdminSettingsServiceImpl(@ComponentImport ActiveObjects ao) {
        this.ao = ao;
    }

    @Override
    public String get(String key, String defaultValue) {
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            return ABSENT.equals(cached) ? defaultValue : cached;
        }
        AdminSettingsEntity[] rows;
        try {
            rows = ao.find(AdminSettingsEntity.class,
                    Query.select().where("SETTING_KEY = ?", key));
        } catch (IllegalStateException e) {
            log.warn("AO ещё не инициализирован, ключ '{}' возвращает значение по умолчанию", key);
            return defaultValue;
        }
        String value = rows.length > 0 ? rows[0].getSettingValue() : ABSENT;
        cache.put(key, value);
        return ABSENT.equals(value) ? defaultValue : value;
    }

    @Override
    public void set(String key, String value) {
        ao.executeInTransaction(() -> {
            AdminSettingsEntity[] rows = ao.find(
                    AdminSettingsEntity.class,
                    Query.select().where("SETTING_KEY = ?", key));

            AdminSettingsEntity entity = rows.length > 0
                    ? rows[0]
                    : ao.create(AdminSettingsEntity.class,
                            new DBParam("SETTING_KEY", key));

            entity.setSettingValue(value);
            entity.save();
            return null;
        });
        cache.invalidate(key);
    }

    @Override
    public boolean isChannelEnabled(NotificationChannel channel) {
        return Boolean.parseBoolean(get(channel.name().toLowerCase() + ".enabled", "false"));
    }
}
