package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

/**
 * AO-сущность: настройки уведомлений одного пользователя.
 * <p>
 * Одна строка на пользователя. {@code USER_KEY} имеет уникальный индекс на уровне БД
 * (защита от race condition при параллельном upsert) и NOT NULL-ограничение.
 */
@Table("USER_NOTIF_SETTINGS")
public interface UserNotificationSettingsEntity extends Entity {

    /** Ключ пользователя Jira ({@code ApplicationUser.getKey()}). */
    @Unique
    @NotNull
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
