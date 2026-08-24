package ru.my.impl;

/**
 * Ключи настроек каналов уведомлений в {@link ru.my.api.AdminSettingsService}.
 * Единственный источник правды для строковых ключей — чтобы клиенты каналов
 * и AdminSettingsResource ссылались на одни и те же константы.
 */
public final class ChannelKeys {

    private ChannelKeys() {}

    public static final String MATTERMOST_DOMAIN  = "mattermost.domain";
    public static final String MATTERMOST_TOKEN   = "mattermost.token";
    public static final String MATTERMOST_BOT_ID  = "mattermost.botId";

    public static final String TELEGRAM_BOT_TOKEN    = "telegram.botToken";
    public static final String TELEGRAM_BOT_USERNAME = "telegram.botUsername";
}
