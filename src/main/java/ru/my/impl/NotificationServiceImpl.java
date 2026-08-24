package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ru.my.api.AdminSettingsService;
import ru.my.api.DelegationService;
import ru.my.api.MessageFormatter;
import ru.my.api.NotificationSender;
import ru.my.api.NotificationService;
import ru.my.api.UserSettingsService;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.emptyList;

/**
 * Оркестратор уведомлений.
 * <p>
 * Pipeline обработки одного события:
 * <ol>
 *   <li>Проверяем diff — если пуст, выходим. Парсинг выполнен в потоке Jira-события.</li>
 *   <li>Снимаем кеш admin-флагов каналов один раз на всё событие.</li>
 *   <li>Обходим наблюдателей: пропускаем неактивных, автора события, отключённых,
 *       тех, кто не следит за этим проектом.</li>
 *   <li>Определяем эффективного получателя через делегирование.</li>
 *   <li>Дедуплицируем: каждый получатель обрабатывается ровно один раз,
 *       даже если на него делегировали несколько наблюдателей.</li>
 *   <li>Для каждого уникального получателя: проверяем его enabled,
 *       берём его каналы, форматируем и отправляем.</li>
 *   <li>Сбой одного канала не останавливает остальные.</li>
 * </ol>
 * <p>
 * Форматтеры ({@link MessageFormatter}) и отправщики ({@link NotificationSender})
 * инжектируются Spring-ом как {@code List<>} через {@link Autowired} после создания бина.
 * Карты строятся в {@link #buildChannelMaps()} и после этого неизменяемы.
 */
@Named
@ExportAsService(NotificationService.class)
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final WatcherManager watcherManager;
    private final UserSettingsService userSettingsService;
    private final DelegationService delegationService;
    private final AdminSettingsService adminSettingsService;

    @Autowired(required = false)
    private List<MessageFormatter> injectedFormatters = emptyList();

    @Autowired(required = false)
    private List<NotificationSender> injectedSenders = emptyList();

    private volatile Map<NotificationChannel, MessageFormatter> formatters = Map.of();
    private volatile Map<NotificationChannel, NotificationSender> senders = Map.of();

    @Inject
    public NotificationServiceImpl(
            @ComponentImport WatcherManager watcherManager,
            UserSettingsService userSettingsService,
            DelegationService delegationService,
            AdminSettingsService adminSettingsService) {
        this.watcherManager = watcherManager;
        this.userSettingsService = userSettingsService;
        this.delegationService = delegationService;
        this.adminSettingsService = adminSettingsService;
    }

    /**
     * Конструктор для unit-тестов — принимает готовые карты форматтеров/отправщиков,
     * обходя Spring-инъекцию и {@link #buildChannelMaps()}.
     */
    public NotificationServiceImpl(
            WatcherManager watcherManager,
            UserSettingsService userSettingsService,
            DelegationService delegationService,
            AdminSettingsService adminSettingsService,
            Map<NotificationChannel, MessageFormatter> formatters,
            Map<NotificationChannel, NotificationSender> senders) {
        this.watcherManager = watcherManager;
        this.userSettingsService = userSettingsService;
        this.delegationService = delegationService;
        this.adminSettingsService = adminSettingsService;
        this.formatters = Map.copyOf(formatters);
        this.senders = Map.copyOf(senders);
    }

    @PostConstruct
    void buildChannelMaps() {
        Map<NotificationChannel, MessageFormatter> fmtMap = new EnumMap<>(NotificationChannel.class);
        for (MessageFormatter f : injectedFormatters) {
            MessageFormatter prev = fmtMap.put(f.channel(), f);
            if (prev != null) {
                log.warn("Конфликт форматтеров для канала {}: {} перезаписан {}",
                        f.channel(), prev.getClass().getSimpleName(), f.getClass().getSimpleName());
            }
        }
        Map<NotificationChannel, NotificationSender> sndMap = new EnumMap<>(NotificationChannel.class);
        for (NotificationSender s : injectedSenders) {
            NotificationSender prev = sndMap.put(s.channel(), s);
            if (prev != null) {
                log.warn("Конфликт отправщиков для канала {}: {} перезаписан {}",
                        s.channel(), prev.getClass().getSimpleName(), s.getClass().getSimpleName());
            }
        }
        this.formatters = Map.copyOf(fmtMap);
        this.senders = Map.copyOf(sndMap);
        log.info("Зарегистрированы каналы: форматтеры={}, отправщики={}",
                formatters.keySet(), senders.keySet());
    }

    @Override
    public void processEvent(Issue issue, ApplicationUser author, DiffResult diff) {
        if (diff.isEmpty()) {
            return;
        }
        if (formatters.isEmpty()) {
            log.warn("Нет зарегистрированных каналов доставки — уведомление по задаче {} пропущено. " +
                    "Возможна гонка инициализации или не зарегистрированы MessageFormatter", issue.getKey());
            return;
        }

        List<ApplicationUser> watchers = watcherManager.getWatchers(issue, Locale.ROOT);

        // admin-флаги читаются один раз на всё событие, а не на каждого получателя
        Map<NotificationChannel, Boolean> channelCache = buildChannelCache();

        // ключ = userKey получателя; putIfAbsent гарантирует отправку ровно один раз
        // даже если несколько наблюдателей делегировали на одного человека
        Map<String, Recipient> uniqueRecipients = new LinkedHashMap<>();

        for (ApplicationUser watcher : watchers) {
            if (!watcher.isActive()) {
                continue;
            }
            if (author != null && Objects.equals(watcher.getKey(), author.getKey())) {
                continue;
            }

            UserSettings watcherSettings = userSettingsService.getSettings(watcher);
            if (!watcherSettings.isEnabled()) {
                continue;
            }
            if (!isProjectIncluded(watcherSettings, issue)) {
                continue;
            }

            ApplicationUser recipient = delegationService.getEffectiveRecipient(watcher);
            if (uniqueRecipients.containsKey(recipient.getKey())) {
                continue;
            }

            // переиспользуем настройки наблюдателя, если делегирования нет
            UserSettings recipientSettings = Objects.equals(recipient.getKey(), watcher.getKey())
                    ? watcherSettings
                    : userSettingsService.getSettings(recipient);

            if (!recipientSettings.isEnabled()) {
                continue;
            }

            uniqueRecipients.put(recipient.getKey(), new Recipient(recipient, recipientSettings));
        }

        for (Recipient r : uniqueRecipients.values()) {
            sendToRecipient(issue, diff, r.user(), r.settings(), channelCache);
        }
    }

    private void sendToRecipient(Issue issue, DiffResult diff, ApplicationUser recipient,
                                 UserSettings settings, Map<NotificationChannel, Boolean> channelCache) {
        // Set защищает от двойной отправки при дублях в List<NotificationChannel>
        for (NotificationChannel channel : new java.util.LinkedHashSet<>(settings.getChannels())) {
            if (Boolean.TRUE.equals(channelCache.get(channel))) {
                sendViaChannel(issue, diff, recipient, channel);
            }
        }
    }

    private void sendViaChannel(Issue issue, DiffResult diff, ApplicationUser recipient,
                                NotificationChannel channel) {
        MessageFormatter formatter = formatters.get(channel);
        NotificationSender sender = senders.get(channel);

        if (formatter == null || sender == null) {
            log.debug("Форматтер или отправщик не найден для канала {}, пропускаем", channel);
            return;
        }

        try {
            String message = formatter.format(issue, diff);
            sender.send(recipient, message);
        } catch (Exception e) {
            log.warn("Ошибка отправки уведомления через {} для {}: {}",
                    channel, recipient.getDisplayName(), e.getMessage());
        }
    }

    /**
     * Снимает флаги включённости каналов один раз на событие.
     * Защищает от N×M запросов в БД при большом числе наблюдателей.
     */
    private Map<NotificationChannel, Boolean> buildChannelCache() {
        Map<NotificationChannel, Boolean> cache = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannel channel : NotificationChannel.values()) {
            cache.put(channel, adminSettingsService.isChannelEnabled(channel));
        }
        return cache;
    }

    private boolean isProjectIncluded(UserSettings settings, Issue issue) {
        List<String> projects = settings.getProjects();
        if (projects.contains("*")) {
            return true;
        }
        var project = issue.getProjectObject();
        if (project == null) {
            log.debug("getProjectObject() вернул null для задачи {}", issue.getKey());
            return false;
        }
        return projects.contains(project.getKey());
    }

    /** Пара (получатель, его настройки) для однократной отправки. */
    private record Recipient(ApplicationUser user, UserSettings settings) {}
}
