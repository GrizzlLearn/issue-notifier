package ru.my.model;

/**
 * Канал доставки уведомлений.
 * <p>
 * Имя константы используется как ключ при хранении списка каналов
 * в {@link ru.my.ao.UserNotificationSettingsEntity#getChannelsRaw()},
 * а также для построения ключей глобальных настроек:
 * {@code channel.name().toLowerCase() + ".enabled"}.
 */
public enum NotificationChannel {

    /** Личное сообщение пользователю через Mattermost-бота. */
    MATTERMOST,

    /** Сообщение в Telegram через Bot API. Требует chat_id от пользователя. */
    TELEGRAM,

    /** Письмо на email из профиля Jira через стандартный MailQueue. */
    EMAIL
}
