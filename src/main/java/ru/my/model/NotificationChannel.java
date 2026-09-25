package ru.my.model;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Канал доставки уведомлений.
 * <p>
 * Имя константы используется как ключ при хранении списка каналов
 * в {@link ru.my.ao.UserNotificationSettingsEntity#getChannelsRaw()},
 * а ключ глобальной настройки строит {@link #enabledKey()} — чтобы новый
 * канал не требовал правок в списках ключей.
 */
public enum NotificationChannel {

    /** Личное сообщение пользователю через Mattermost-бота. */
    MATTERMOST(true),

    /** Сообщение в Telegram через Bot API. Требует chat_id от пользователя. */
    TELEGRAM(true),

    /** Письмо на email из профиля Jira через стандартный MailQueue. */
    EMAIL(false);

    private final boolean supportsActionTemplates;

    NotificationChannel(boolean supportsActionTemplates) {
        this.supportsActionTemplates = supportsActionTemplates;
    }

    /** Ключ настройки «канал включён», например {@code "telegram.enabled"}. */
    public String enabledKey() {
        return name().toLowerCase(Locale.ROOT) + ".enabled";
    }

    /**
     * Каналы, по которым рассылаются уведомления о действиях.
     * По email уходят только изменения полей, шаблонов действий у него нет.
     */
    public static List<NotificationChannel> actionChannels() {
        return ACTION_CHANNELS;
    }

    private static final List<NotificationChannel> ACTION_CHANNELS = Stream.of(values())
            .filter(c -> c.supportsActionTemplates)
            .toList();
}
