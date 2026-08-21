package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * AO-сущность: настройки уведомлений одного пользователя.
 * <p>
 * Одна строка на пользователя. Уникальность по {@code USER_KEY} обеспечивается
 * upsert-логикой в {@link ru.my.impl.UserSettingsServiceImpl}.
 */
@Table("USER_NOTIFICATION_SETTINGS")
public interface UserNotificationSettingsEntity extends Entity {

    /** Ключ пользователя Jira ({@code ApplicationUser.getKey()}). */
    String getUserKey();
    void setUserKey(String userKey);

    /** Глобальный признак включения уведомлений для пользователя. */
    boolean isEnabled();
    void setEnabled(boolean enabled);

    /**
     * Ключи проектов через запятую: {@code "PROJ,TEST"} или {@code "*"} для всех проектов.
     */
    @StringLength(StringLength.UNLIMITED)
    String getProjectsRaw();
    void setProjectsRaw(String projectsRaw);

    /**
     * Активные каналы уведомлений через запятую: {@code "MATTERMOST,EMAIL"}.
     * Соответствует именам {@link ru.my.model.NotificationChannel}.
     */
    String getChannelsRaw();
    void setChannelsRaw(String channelsRaw);

    /**
     * Идентификатор чата пользователя в Telegram.
     * Заполняется вручную в настройках. {@code null} — Telegram не настроен.
     */
    String getTelegramChatId();
    void setTelegramChatId(String telegramChatId);
}
