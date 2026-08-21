package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

/**
 * AO-сущность: глобальные настройки плагина в формате key-value.
 * <p>
 * Каждая настройка — отдельная строка. Такой формат позволяет добавлять
 * новые ключи без миграции схемы. Доступ только через {@link ru.my.api.AdminSettingsService}.
 * <p>
 * Список ключей: {@code mattermost.enabled}, {@code mattermost.domain},
 * {@code mattermost.botToken}, {@code mattermost.botId},
 * {@code telegram.enabled}, {@code telegram.botToken}, {@code email.enabled}.
 */
@Table("ADMIN_SETTINGS")
public interface AdminSettingsEntity extends Entity {

    /** Ключ настройки, например {@code "mattermost.botToken"}. */
    @Unique
    @NotNull
    String getSettingKey();
    void setSettingKey(String settingKey);

    /** Значение настройки. */
    @StringLength(StringLength.UNLIMITED)
    String getSettingValue();
    void setSettingValue(String settingValue);
}
