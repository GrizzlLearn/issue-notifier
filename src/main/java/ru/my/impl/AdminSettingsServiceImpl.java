package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.DBParam;
import net.java.ao.Query;
import ru.my.ao.AdminSettingsEntity;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Реализация {@link AdminSettingsService} на базе Active Objects.
 * Настройки хранятся в таблице {@code ADMIN_SETTINGS} в формате key-value.
 * Запись создаётся при первом вызове {@link #set}, до этого {@link #get}
 * возвращает переданное значение по умолчанию.
 */
@Named
@ExportAsService(AdminSettingsService.class)
public class AdminSettingsServiceImpl implements AdminSettingsService {

    private final ActiveObjects ao;

    @Inject
    public AdminSettingsServiceImpl(@ComponentImport ActiveObjects ao) {
        this.ao = ao;
    }

    @Override
    public String get(String key, String defaultValue) {
        AdminSettingsEntity[] rows = ao.find(
                AdminSettingsEntity.class,
                Query.select().where("SETTING_KEY = ?", key));
        return rows.length > 0 ? rows[0].getSettingValue() : defaultValue;
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
    }

    @Override
    public boolean isChannelEnabled(NotificationChannel channel) {
        return Boolean.parseBoolean(get(channel.name().toLowerCase() + ".enabled", "false"));
    }
}
