package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheException;
import com.google.common.base.Throwables;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettingsBuilder;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.impl.util.AoUpsert;
import net.java.ao.Query;
import ru.my.ao.AdminSettingsEntity;
import ru.my.api.AdminSettingsService;
import ru.my.model.LoggingSettings.Area;
import ru.my.model.NotificationChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Реализация {@link AdminSettingsService} на базе Active Objects.
 * Настройки хранятся в таблице {@code ADMIN_SETTINGS} в формате key-value.
 * Запись создаётся при первом вызове {@link #set}, до этого {@link #get}
 * возвращает переданное значение по умолчанию.
 * <p>
 * Результаты кешируются на 60 секунд — настройки меняются редко и только вручную.
 * {@link #set} инвалидирует запись для изменённого ключа.
 * <p>
 * Кеш кластерный ({@code replicateViaInvalidation}): администратор правит настройки
 * на одной ноде, а канал должен выключиться на всех. Реплицируются только
 * инвалидации — значения остаются локальными, сериализовать их не требуется.
 */
@Named
@ExportAsService(AdminSettingsService.class)
public class AdminSettingsServiceImpl implements AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsServiceImpl.class);

    /** Сентинел в кеше: ключ отсутствует в БД — вернуть defaultValue вызывающему. */
    private static final String ABSENT = "\0";

    private final ActiveObjects ao;
    private final Cache<String, String> cache;
    private final PluginLogging pluginLogging;

    @Inject
    public AdminSettingsServiceImpl(@ComponentImport ActiveObjects ao,
                                    @ComponentImport CacheManager cacheManager,
                                    PluginLogging pluginLogging) {
        this.ao = ao;
        this.pluginLogging = pluginLogging;
        this.cache = cacheManager.getCache(
                AdminSettingsServiceImpl.class.getName() + ".settings",
                this::load,
                new CacheSettingsBuilder()
                        .maxEntries(1_000)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .replicateViaInvalidation()
                        .build());
    }

    /**
     * Настройки логирования хранятся здесь же, поэтому и применяются отсюда: они
     * пережили перезапуск Jira — значит уровни надо вернуть до первого уведомления.
     * {@link #get} сам переживает неготовый AO, так что читать можно уже на старте.
     */
    @PostConstruct
    public void applyLoggingSettings() {
        pluginLogging.applyAreas(enabledLogAreas());
    }

    private Set<Area> enabledLogAreas() {
        Set<Area> enabled = EnumSet.noneOf(Area.class);
        Arrays.stream(Area.values())
                .filter(area -> Boolean.parseBoolean(get(area.key(), "false")))
                .forEach(enabled::add);
        return enabled;
    }

    @Override
    public String get(String key, String defaultValue) {
        try {
            String value = cache.get(key);
            return ABSENT.equals(value) ? defaultValue : value;
        } catch (CacheException e) {
            if (!(Throwables.getRootCause(e) instanceof IllegalStateException)) {
                throw e;
            }
            log.warn("AO ещё не инициализирован, ключ '{}' возвращает значение по умолчанию", key);
            return defaultValue;
        }
    }

    private String load(String key) {
        AdminSettingsEntity[] rows = ao.find(AdminSettingsEntity.class,
                Query.select().where("SETTING_KEY = ?", key));
        return rows.length > 0 ? rows[0].getSettingValue() : ABSENT;
    }

    @Override
    public void set(String key, String value) {
        AoUpsert.save(ao, AdminSettingsEntity.class, "SETTING_KEY", key,
                entity -> entity.setSettingValue(value));
        cache.remove(key);
        if (Area.byKey(key).isPresent()) {
            pluginLogging.applyAreas(enabledLogAreas());
        }
    }

    @Override
    public boolean isChannelEnabled(NotificationChannel channel) {
        return Boolean.parseBoolean(get(channel.enabledKey(), "false"));
    }
}
