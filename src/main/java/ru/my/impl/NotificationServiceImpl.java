package ru.my.impl;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.api.DelegationService;
import ru.my.api.MessageFormatter;
import ru.my.api.NotificationSender;
import ru.my.api.NotificationService;
import ru.my.api.UserSettingsService;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Оркестратор уведомлений.
 * <p>
 * Pipeline обработки одного события:
 * <ol>
 *   <li>Парсим diff — если пусто, выходим;</li>
 *   <li>Получаем список наблюдателей задачи;</li>
 *   <li>Для каждого наблюдателя: проверяем его настройки (enabled, проекты);</li>
 *   <li>Определяем фактического получателя через делегирование;</li>
 *   <li>Получаем каналы получателя, фильтруем по admin-флагам;</li>
 *   <li>Форматируем и отправляем; сбой одного канала не останавливает остальные.</li>
 * </ol>
 * <p>
 * Форматтеры и отправщики регистрируются через {@link #registerFormatter} /
 * {@link #registerSender} — каждая реализация (Email, Mattermost, Telegram)
 * подключает себя при инициализации Spring-контекста.
 */
@Named
@ExportAsService(NotificationService.class)
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final WatcherManager watcherManager;
    private final UserSettingsService userSettingsService;
    private final DelegationService delegationService;
    private final AdminSettingsService adminSettingsService;

    private final Map<NotificationChannel, MessageFormatter> formatters =
            new EnumMap<>(NotificationChannel.class);
    private final Map<NotificationChannel, NotificationSender> senders =
            new EnumMap<>(NotificationChannel.class);

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
     * Пакетный конструктор для unit-тестов — позволяет подать готовые моки
     * форматтеров и отправщиков без регистрации через register-методы.
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
        this.formatters.putAll(formatters);
        this.senders.putAll(senders);
    }

    /**
     * Регистрирует форматтер для своего канала.
     * Вызывается из конструктора каждой реализации {@link MessageFormatter}.
     *
     * @param formatter реализация форматтера
     */
    public void registerFormatter(MessageFormatter formatter) {
        formatters.put(formatter.channel(), formatter);
    }

    /**
     * Регистрирует отправщик для своего канала.
     * Вызывается из конструктора каждой реализации {@link NotificationSender}.
     *
     * @param sender реализация отправщика
     */
    public void registerSender(NotificationSender sender) {
        senders.put(sender.channel(), sender);
    }

    @Override
    public void processEvent(IssueEvent event) {
        DiffResult diff = DiffFormatter.parse(event.getChangeLog());
        if (diff.isEmpty()) {
            return;
        }

        Issue issue = event.getIssue();
        List<ApplicationUser> watchers = watcherManager.getWatchers(issue, Locale.ROOT);

        for (ApplicationUser watcher : watchers) {
            processForWatcher(issue, diff, watcher);
        }
    }

    private void processForWatcher(Issue issue, DiffResult diff, ApplicationUser watcher) {
        UserSettings watcherSettings = userSettingsService.getSettings(watcher);

        if (!watcherSettings.isEnabled()) {
            return;
        }
        if (!isProjectIncluded(watcherSettings, issue)) {
            return;
        }

        ApplicationUser recipient = delegationService.getEffectiveRecipient(watcher);
        UserSettings recipientSettings = userSettingsService.getSettings(recipient);

        for (NotificationChannel channel : recipientSettings.getChannels()) {
            sendViaChannel(issue, diff, recipient, channel);
        }
    }

    private void sendViaChannel(Issue issue, DiffResult diff, ApplicationUser recipient,
                                NotificationChannel channel) {
        if (!adminSettingsService.isChannelEnabled(channel)) {
            return;
        }

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

    private boolean isProjectIncluded(UserSettings settings, Issue issue) {
        List<String> projects = settings.getProjects();
        return projects.contains("*") || projects.contains(issue.getProjectObject().getKey());
    }
}
