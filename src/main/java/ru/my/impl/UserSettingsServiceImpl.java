package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettingsBuilder;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.impl.util.AoUpsert;
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
 * <p>
 * Кеш кластерный: пользователь меняет настройки на той ноде, куда попал его
 * запрос, а рассылку может вести другая — реплицируемая инвалидация не даёт ей
 * работать со старым списком каналов.
 */
@Named
@ExportAsService(UserSettingsService.class)
public class UserSettingsServiceImpl implements UserSettingsService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsServiceImpl.class);

    private final ActiveObjects ao;
    private final Cache<String, UserSettings> cache;

    @Inject
    public UserSettingsServiceImpl(@ComponentImport ActiveObjects ao,
                                   @ComponentImport CacheManager cacheManager) {
        this.ao = ao;
        // maxEntries обязателен: без границы кеш растёт по числу пользователей инстанса
        this.cache = cacheManager.getCache(
                UserSettingsServiceImpl.class.getName() + ".settings",
                this::loadFromAO,
                new CacheSettingsBuilder()
                        .maxEntries(10_000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .replicateViaInvalidation()
                        .build());
    }

    @Override
    public UserSettings getSettings(ApplicationUser user) {
        return cache.get(user.getKey());
    }

    @Override
    public void saveSettings(ApplicationUser user, UserSettings settings) {
        log.debug("Настройки {}: уведомления {}, каналы {}, проектов {}, текст комментария {}",
                user.getKey(), settings.isEnabled() ? "включены" : "выключены",
                settings.getChannels(), settings.getProjects().size(),
                settings.isCommentTextHidden() ? "скрыт" : "показывается");
        AoUpsert.save(ao, UserNotificationSettingsEntity.class, "USER_KEY", user.getKey(), entity -> {
            entity.setEnabled(settings.isEnabled());
            entity.setProjectsRaw(String.join(",", settings.getProjects()));
            entity.setChannelsRaw(settings.getChannels().stream()
                    .map(NotificationChannel::name)
                    .collect(Collectors.joining(",")));
            entity.setTelegramChatId(settings.getTelegramChatId());
            entity.setCommentTextHidden(settings.isCommentTextHidden());
        });
        cache.remove(user.getKey());
    }

    private UserSettings loadFromAO(String userKey) {
        UserNotificationSettingsEntity[] rows = ao.find(
                UserNotificationSettingsEntity.class,
                Query.select().where("USER_KEY = ?", userKey));
        return rows.length == 0 ? UserSettings.defaultSettings() : toModel(rows[0]);
    }

    /** Конвертирует AO-сущность в модель. */
    private UserSettings toModel(UserNotificationSettingsEntity entity) {
        return UserSettings.builder()
                .enabled(entity.isEnabled())
                .projects(parseList(entity.getProjectsRaw()))
                .channels(parseChannels(entity.getChannelsRaw()))
                .telegramChatId(entity.getTelegramChatId())
                .commentTextHidden(entity.isCommentTextHidden())
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
