package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.java.ao.DBParam;
import net.java.ao.Query;
import ru.my.ao.UserNotificationSettingsEntity;
import ru.my.api.UserSettingsService;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Реализация {@link UserSettingsService} на базе Active Objects.
 * Использует upsert-паттерн: при сохранении ищет существующую запись по
 * {@code USER_KEY} и обновляет её, либо создаёт новую.
 */
@Named
@ExportAsService(UserSettingsService.class)
public class UserSettingsServiceImpl implements UserSettingsService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsServiceImpl.class);

    private final ActiveObjects ao;
    private final Cache<String, UserSettings> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    @Inject
    public UserSettingsServiceImpl(@ComponentImport ActiveObjects ao) {
        this.ao = ao;
    }

    @Override
    public UserSettings getSettings(ApplicationUser user) {
        String key = user.getKey();
        UserSettings cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        UserSettings settings = loadFromAO(user);
        cache.put(key, settings);
        return settings;
    }

    @Override
    public void saveSettings(ApplicationUser user, UserSettings settings) {
        ao.executeInTransaction(() -> {
            UserNotificationSettingsEntity[] rows = ao.find(
                    UserNotificationSettingsEntity.class,
                    Query.select().where("USER_KEY = ?", user.getKey()));

            UserNotificationSettingsEntity entity = rows.length > 0
                    ? rows[0]
                    : ao.create(UserNotificationSettingsEntity.class,
                            new DBParam("USER_KEY", user.getKey()));

            entity.setEnabled(settings.isEnabled());
            entity.setProjectsRaw(String.join(",", settings.getProjects()));
            entity.setChannelsRaw(settings.getChannels().stream()
                    .map(NotificationChannel::name)
                    .collect(Collectors.joining(",")));
            entity.setTelegramChatId(settings.getTelegramChatId());
            entity.save();
            return null;
        });
        cache.invalidate(user.getKey());
    }

    private UserSettings loadFromAO(ApplicationUser user) {
        UserNotificationSettingsEntity[] rows = ao.find(
                UserNotificationSettingsEntity.class,
                Query.select().where("USER_KEY = ?", user.getKey()));
        return rows.length == 0 ? UserSettings.defaultSettings() : toModel(rows[0]);
    }

    /** Конвертирует AO-сущность в модель. */
    private UserSettings toModel(UserNotificationSettingsEntity entity) {
        return UserSettings.builder()
                .enabled(entity.isEnabled())
                .projects(parseList(entity.getProjectsRaw()))
                .channels(parseChannels(entity.getChannelsRaw()))
                .telegramChatId(entity.getTelegramChatId())
                .build();
    }

    /** Разбирает строку через запятую в список строк; пустая строка → список из "*". */
    private List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("*");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Разбирает строку через запятую в список каналов; пустая строка → пустой список.
     * Неизвестные значения (удалённый канал, опечатка при ручной правке) пропускаются
     * с предупреждением — пользователь не теряет оставшиеся каналы.
     */
    private List<NotificationChannel> parseChannels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .flatMap(name -> {
                    try {
                        return java.util.stream.Stream.of(NotificationChannel.valueOf(name));
                    } catch (IllegalArgumentException e) {
                        log.warn("Неизвестный канал '{}' в настройках пользователя, пропускаем", name);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
    }
}
