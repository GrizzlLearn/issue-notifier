package ru.my.api;

import ru.my.model.NotificationChannel;

/**
 * Сервис глобальных настроек плагина.
 * Данные хранятся в Active Objects ({@code ADMIN_SETTINGS}) в формате key-value.
 * Изменение настроек доступно только администратору Jira через REST API.
 */
public interface AdminSettingsService {

    /**
     * Возвращает значение настройки по ключу.
     *
     * @param key          ключ настройки, например {@code "mattermost.botToken"}
     * @param defaultValue значение по умолчанию, если ключ не найден
     * @return сохранённое значение или {@code defaultValue}
     */
    String get(String key, String defaultValue);

    /**
     * Сохраняет значение настройки (upsert: создаёт или обновляет).
     *
     * @param key   ключ настройки
     * @param value новое значение
     */
    void set(String key, String value);

    /**
     * Проверяет, включён ли канал уведомлений в глобальных настройках.
     * Ключ в хранилище: {@code channel.name().toLowerCase() + ".enabled"}.
     *
     * @param channel канал уведомлений
     * @return {@code true} если канал включён администратором
     */
    boolean isChannelEnabled(NotificationChannel channel);
}
