package ru.my.impl;

import com.atlassian.jira.issue.CustomFieldManager;
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
import ru.my.model.ActionScope;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
 * инжектируются Spring-ом как {@code List<>} через конструктор; Spring собирает все бины
 * нужного типа из контекста плагина, при отсутствии — передаёт пустой список.
 * Карты строятся в конструкторе и после этого неизменяемы.
 */
@Named
@ExportAsService(NotificationService.class)
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final WatcherManager watcherManager;
    private final CustomFieldManager customFieldManager;
    private final UserSettingsService userSettingsService;
    private final DelegationService delegationService;
    private final AdminSettingsService adminSettingsService;

    private final Map<NotificationChannel, MessageFormatter> formatters;
    private final Map<NotificationChannel, NotificationSender> senders;

    @Inject
    public NotificationServiceImpl(
            @ComponentImport WatcherManager watcherManager,
            @ComponentImport CustomFieldManager customFieldManager,
            UserSettingsService userSettingsService,
            DelegationService delegationService,
            AdminSettingsService adminSettingsService,
            List<MessageFormatter> formatters,
            List<NotificationSender> senders) {
        this.watcherManager = watcherManager;
        this.customFieldManager = customFieldManager;
        this.userSettingsService = userSettingsService;
        this.delegationService = delegationService;
        this.adminSettingsService = adminSettingsService;
        this.formatters = buildFormatterMap(formatters);
        this.senders = buildSenderMap(senders);
        log.info("Зарегистрированы каналы: форматтеры={}, отправщики={}",
                this.formatters.keySet(), this.senders.keySet());
    }

    /** Конструктор для unit-тестов — принимает готовые карты, обходя Spring-инжекцию. */
    public NotificationServiceImpl(
            WatcherManager watcherManager,
            CustomFieldManager customFieldManager,
            UserSettingsService userSettingsService,
            DelegationService delegationService,
            AdminSettingsService adminSettingsService,
            Map<NotificationChannel, MessageFormatter> formatters,
            Map<NotificationChannel, NotificationSender> senders) {
        this.watcherManager = watcherManager;
        this.customFieldManager = customFieldManager;
        this.userSettingsService = userSettingsService;
        this.delegationService = delegationService;
        this.adminSettingsService = adminSettingsService;
        this.formatters = Map.copyOf(formatters);
        this.senders = Map.copyOf(senders);
    }

    private static Map<NotificationChannel, MessageFormatter> buildFormatterMap(List<MessageFormatter> list) {
        Map<NotificationChannel, MessageFormatter> map = new EnumMap<>(NotificationChannel.class);
        for (MessageFormatter f : list) {
            MessageFormatter prev = map.put(f.channel(), f);
            if (prev != null) {
                log.warn("Конфликт форматтеров для канала {}: {} перезаписан {}",
                        f.channel(), prev.getClass().getSimpleName(), f.getClass().getSimpleName());
            }
        }
        return Map.copyOf(map);
    }

    private static Map<NotificationChannel, NotificationSender> buildSenderMap(List<NotificationSender> list) {
        Map<NotificationChannel, NotificationSender> map = new EnumMap<>(NotificationChannel.class);
        for (NotificationSender s : list) {
            NotificationSender prev = map.put(s.channel(), s);
            if (prev != null) {
                log.warn("Конфликт отправщиков для канала {}: {} перезаписан {}",
                        s.channel(), prev.getClass().getSimpleName(), s.getClass().getSimpleName());
            }
        }
        return Map.copyOf(map);
    }

    @Override
    public void processEvent(Issue issue, ApplicationUser author, DiffResult diff,
                             Collection<ApplicationUser> exclude) {
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

        Set<String> excludedKeys = exclude.stream().map(ApplicationUser::getKey).collect(Collectors.toSet());

        for (Recipient r : collectRecipients(issue, author, watchers, true)) {
            // тому, кому по этому событию уже ушло уведомление о действии,
            // второе сообщение об изменении полей не отправляем
            if (excludedKeys.contains(r.user().getKey())) {
                continue;
            }
            sendToRecipient(issue, diff, r.user(), r.settings(), channelCache);
        }
    }

    @Override
    public List<ApplicationUser> processAction(Issue issue, ApplicationUser author, NotificationAction action,
                                               List<ApplicationUser> recipients, Map<String, String> placeholders) {
        if (!Boolean.parseBoolean(adminSettingsService.get(ActionTemplates.enabledKey(action), "false"))) {
            return List.of();
        }
        if (!isInScope(action, issue)) {
            return List.of();
        }

        // явный список получателей (например, упомянутые в комментарии) имеет
        // приоритет над настройкой — он относится к конкретному событию
        List<ApplicationUser> base = (recipients == null || recipients.isEmpty())
                ? IssueRecipients.resolve(
                        adminSettingsService.get(ActionTemplates.recipientsKey(action), ""),
                        issue, watcherManager, customFieldManager)
                : recipients;

        Map<NotificationChannel, Boolean> channelCache = buildChannelCache();

        List<ApplicationUser> notified = new ArrayList<>();

        // Пользовательский фильтр проектов здесь не применяется: он относится
        // к наблюдению за изменениями задач, а область действий задаёт администратор.
        for (Recipient r : collectRecipients(issue, author, base, false)) {
            boolean sent = false;
            // Set защищает от двойной отправки при дублях в List<NotificationChannel>
            for (NotificationChannel channel : new LinkedHashSet<>(r.settings().getChannels())) {
                if (Boolean.TRUE.equals(channelCache.get(channel))) {
                    sent |= sendAction(action, channel, r.user(), placeholders);
                }
            }
            if (sent) {
                notified.add(r.user());
            }
        }
        return List.copyOf(notified);
    }

    /**
     * Работает ли действие в проекте задачи: область {@code all} — везде,
     * {@code selected} — только в проектах, отмеченных на вкладке «Проекты».
     * Действие с фиксированной областью настройку не читает.
     */
    private boolean isInScope(NotificationAction action, Issue issue) {
        ActionScope scope = action.isScopeFixed()
                ? action.defaultScope()
                : ActionScope.byKey(adminSettingsService.get(
                        ActionTemplates.scopeKey(action), action.defaultScope().key()));
        if (ActionScope.SELECTED != scope) {
            return true;
        }
        var project = issue.getProjectObject();
        var category = project != null ? project.getProjectCategoryObject() : null;
        return PortalProjects.contains(
                adminSettingsService.get(PortalProjects.KEY, ""),
                adminSettingsService.get(PortalProjects.CATEGORIES_KEY, ""),
                project != null ? project.getKey() : null,
                category != null ? category.getId() : null);
    }

    /** @return {@code true} — сообщение ушло; иначе шаблон пуст, канала нет или отправка упала. */
    private boolean sendAction(NotificationAction action, NotificationChannel channel,
                               ApplicationUser recipient, Map<String, String> placeholders) {
        String template = adminSettingsService.get(ActionTemplates.templateKey(action, channel), "");
        if (template.isBlank()) {
            // шаблон не задан администратором — по этому каналу не шлём
            return false;
        }
        NotificationSender sender = senders.get(channel);
        if (sender == null) {
            log.debug("Отправщик не найден для канала {}, действие {} пропущено", channel, action);
            return false;
        }
        try {
            sender.send(recipient, ActionTemplates.render(template, placeholders, channel));
            return true;
        } catch (Exception e) {
            log.warn("Ошибка отправки уведомления о действии {} через {} для {}: {}",
                    action, channel, recipient.getDisplayName(), e.getMessage());
            return false;
        }
    }

    /**
     * Отбирает итоговых получателей: отсеивает неактивных, автора события
     * и отключивших уведомления, применяет делегирование и дедуплицирует —
     * каждый получатель попадает в результат ровно один раз, даже если на него
     * делегировали несколько наблюдателей.
     *
     * @param applyUserProjectFilter учитывать ли список проектов в настройках
     *                               получателя; он относится только к уведомлениям
     *                               об изменениях задач, за которыми тот наблюдает
     */
    private Collection<Recipient> collectRecipients(Issue issue, ApplicationUser author,
                                                    List<ApplicationUser> candidates,
                                                    boolean applyUserProjectFilter) {
        Map<String, Recipient> uniqueRecipients = new LinkedHashMap<>();

        for (ApplicationUser candidate : candidates) {
            if (!candidate.isActive()) {
                continue;
            }
            if (author != null && Objects.equals(candidate.getKey(), author.getKey())) {
                continue;
            }

            UserSettings candidateSettings = userSettingsService.getSettings(candidate);
            if (!candidateSettings.isEnabled()) {
                continue;
            }
            if (applyUserProjectFilter && !isProjectIncluded(candidateSettings, issue)) {
                continue;
            }

            for (ApplicationUser recipient : delegationService.getEffectiveRecipients(candidate)) {
                if (uniqueRecipients.containsKey(recipient.getKey())) {
                    continue;
                }

                // переиспользуем настройки кандидата, если делегирования нет
                UserSettings recipientSettings = Objects.equals(recipient.getKey(), candidate.getKey())
                        ? candidateSettings
                        : userSettingsService.getSettings(recipient);

                if (!recipientSettings.isEnabled()) {
                    continue;
                }

                uniqueRecipients.put(recipient.getKey(), new Recipient(recipient, recipientSettings));
            }
        }
        return uniqueRecipients.values();
    }

    private void sendToRecipient(Issue issue, DiffResult diff, ApplicationUser recipient,
                                 UserSettings settings, Map<NotificationChannel, Boolean> channelCache) {
        // Set защищает от двойной отправки при дублях в List<NotificationChannel>
        for (NotificationChannel channel : new LinkedHashSet<>(settings.getChannels())) {
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
    private static final class Recipient {
        private final ApplicationUser user;
        private final UserSettings settings;

        Recipient(ApplicationUser user, UserSettings settings) {
            this.user = user;
            this.settings = settings;
        }

        ApplicationUser user()     { return user; }
        UserSettings settings()    { return settings; }
    }
}
