package ru.my.impl;

import com.atlassian.jira.event.issue.IssueEvent;
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
 *   <li>Парсим diff — если пусто, выходим;</li>
 *   <li>Получаем список наблюдателей задачи;</li>
 *   <li>Для каждого наблюдателя: пропускаем автора события и отключённых;</li>
 *   <li>Проверяем проектный фильтр наблюдателя;</li>
 *   <li>Определяем фактического получателя через делегирование;</li>
 *   <li>Если делегат отключил уведомления — пропускаем;</li>
 *   <li>Получаем каналы получателя, фильтруем по admin-флагам;</li>
 *   <li>Форматируем и отправляем; сбой одного канала не останавливает остальные.</li>
 * </ol>
 * <p>
 * Форматтеры ({@link MessageFormatter}) и отправщики ({@link NotificationSender})
 * инжектируются Spring-ом как {@code List<>} после создания бина — каждый
 * {@link Named}-компонент, реализующий эти интерфейсы, будет собран автоматически.
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

    // инжектируются через @Autowired(required=false) до вызова @PostConstruct
    @Autowired(required = false)
    private List<MessageFormatter> injectedFormatters = emptyList();

    @Autowired(required = false)
    private List<NotificationSender> injectedSenders = emptyList();

    // строятся в @PostConstruct и дальше только читаются
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

    /**
     * Вызывается Spring-ом после завершения всей инъекции зависимостей.
     * Строит неизменяемые карты из списков форматтеров и отправщиков.
     */
    @PostConstruct
    void buildChannelMaps() {
        Map<NotificationChannel, MessageFormatter> fmtMap = new EnumMap<>(NotificationChannel.class);
        for (MessageFormatter f : injectedFormatters) {
            fmtMap.put(f.channel(), f);
        }
        Map<NotificationChannel, NotificationSender> sndMap = new EnumMap<>(NotificationChannel.class);
        for (NotificationSender s : injectedSenders) {
            sndMap.put(s.channel(), s);
        }
        this.formatters = Map.copyOf(fmtMap);
        this.senders = Map.copyOf(sndMap);
        log.info("Зарегистрированы каналы уведомлений: форматтеры={}, отправщики={}",
                formatters.keySet(), senders.keySet());
    }

    @Override
    public void processEvent(IssueEvent event) {
        DiffResult diff = DiffFormatter.parse(event.getChangeLog());
        if (diff.isEmpty()) {
            return;
        }

        Issue issue = event.getIssue();
        ApplicationUser author = event.getUser(); // инициатор изменения
        List<ApplicationUser> watchers = watcherManager.getWatchers(issue, Locale.ROOT);

        for (ApplicationUser watcher : watchers) {
            processForWatcher(issue, diff, watcher, author);
        }
    }

    private void processForWatcher(Issue issue, DiffResult diff,
                                   ApplicationUser watcher, ApplicationUser author) {
        // автор не получает уведомлений о своих собственных изменениях
        if (author != null && Objects.equals(watcher.getKey(), author.getKey())) {
            return;
        }

        UserSettings watcherSettings = userSettingsService.getSettings(watcher);

        if (!watcherSettings.isEnabled()) {
            return;
        }
        if (!isProjectIncluded(watcherSettings, issue)) {
            return;
        }

        ApplicationUser recipient = delegationService.getEffectiveRecipient(watcher);

        // настройки получателя: если делегат тот же — переиспользуем, иначе загружаем отдельно
        UserSettings recipientSettings = Objects.equals(recipient.getKey(), watcher.getKey())
                ? watcherSettings
                : userSettingsService.getSettings(recipient);

        // если делегат выключил уведомления — делегированные тоже не присылаем
        if (!recipientSettings.isEnabled()) {
            return;
        }

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
}
