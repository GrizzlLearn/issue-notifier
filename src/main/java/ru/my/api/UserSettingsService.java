package ru.my.api;

import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.UserSettings;

/**
 * Сервис для чтения и сохранения настроек уведомлений пользователя.
 * Данные хранятся в Active Objects ({@code USER_NOTIFICATION_SETTINGS}).
 */
public interface UserSettingsService {

    /**
     * Возвращает настройки пользователя из БД.
     * Если пользователь ещё не настраивал плагин — возвращает {@link UserSettings#defaultSettings()}.
     *
     * @param user пользователь Jira
     * @return настройки; никогда не {@code null}
     */
    UserSettings getSettings(ApplicationUser user);

    /**
     * Сохраняет настройки пользователя (upsert: создаёт или обновляет).
     *
     * @param user     пользователь Jira
     * @param settings новые настройки
     */
    void saveSettings(ApplicationUser user, UserSettings settings);
}
