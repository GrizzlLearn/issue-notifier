package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;
import ru.my.api.DelegationService;
import ru.my.api.MessageFormatter;
import ru.my.api.NotificationSender;
import ru.my.api.UserSettingsService;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import ru.my.model.ActionTemplates;
import ru.my.model.CommentTextMode;
import ru.my.model.WatchedFields;
import ru.my.model.ProjectContext;
import ru.my.model.ProjectContexts;

/**
 * Проверяет оркестрацию: фильтрацию наблюдателей, делегирование,
 * выбор каналов, исключение автора события и изоляцию ошибок отправщика.
 */
@RunWith(MockitoJUnitRunner.class)
public class NotificationServiceTest {

    @Mock private WatcherManager watcherManager;
    @Mock private CustomFieldManager customFieldManager;
    @Mock private PermissionManager permissionManager;
    @Mock private UserSettingsService userSettingsService;
    @Mock private DelegationService delegationService;
    @Mock private AdminSettingsService adminSettingsService;
    @Mock private MessageFormatter formatter;
    @Mock private NotificationSender sender;

    private NotificationServiceImpl service;
    private ApplicationUser watcher;
    private Issue issue;

    // diff-фикстуры: с изменениями и без
    private static final DiffResult EMPTY_DIFF = new DiffResult(List.of());
    private static final DiffResult NON_EMPTY_DIFF = new DiffResult(
            List.of(new DiffResult.FieldChange("Status", "Open", "In Progress")));

    @Before
    public void setUp() {
        Map<NotificationChannel, MessageFormatter> formatters = new EnumMap<>(NotificationChannel.class);
        formatters.put(NotificationChannel.MATTERMOST, formatter);
        Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
        senders.put(NotificationChannel.MATTERMOST, sender);

        // рассылка наблюдателям выключена по умолчанию — в тестах processEvent включаем,
        // кроме теста, который эту дефолтную выключенность и проверяет.
        // Матчер на дефолтное значение любой: иначе смена дефолта в коде оставила бы
        // мок без стаба, processEvent тихо выходил бы, а негативные тесты остались
        // бы зелёными по неверной причине
        lenient().when(adminSettingsService.get(eq(ActionTemplates.WATCHERS_ENABLED_KEY), anyString()))
                .thenReturn("true");

        // право видеть задачу есть у всех, кроме отдельно оговорённых тестов
        lenient().when(permissionManager.hasPermission(
                eq(ProjectPermissions.BROWSE_PROJECTS), any(Issue.class), any(ApplicationUser.class)))
                .thenReturn(true);

        service = new NotificationServiceImpl(
                watcherManager, customFieldManager, permissionManager, userSettingsService,
                delegationService, adminSettingsService, formatters, senders);

        watcher = new MockApplicationUser("alice", "Alice", "alice@example.com");

        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJ");
        issue = mock(Issue.class);
        when(issue.getProjectObject()).thenReturn(project);
    }

    @Test
    public void skipsWhenDiffIsEmpty() {
        service.processEvent(issue, null, EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
    }

    @Test
    public void skipsWhenNoFormattersRegistered() {
        // Пустые карты — гонка инициализации или незарегистрированные каналы
        NotificationServiceImpl emptyService = new NotificationServiceImpl(
                watcherManager, customFieldManager, permissionManager, userSettingsService,
                delegationService, adminSettingsService, Map.of(), Map.of());

        emptyService.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    @Test
    public void skipsEventWhenWatcherNotificationsDisabledByAdmin() {
        when(adminSettingsService.get(eq(ActionTemplates.WATCHERS_ENABLED_KEY), anyString())).thenReturn("false");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    /** Записи в настройках нет — рассылка наблюдателям не идёт: это дефолт плагина. */
    @Test
    public void skipsEventWhenWatcherNotificationsNotConfigured() {
        when(adminSettingsService.get(eq(ActionTemplates.WATCHERS_ENABLED_KEY), anyString())).thenReturn("");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    /**
     * Админ оставил только «Описание», а менялся статус — уведомлять не о чем,
     * наблюдателей даже не спрашиваем.
     */
    @Test
    public void skipsEventWhenChangedFieldGroupIsUnchecked() {
        when(adminSettingsService.get(WatchedFields.KEY, "")).thenReturn(WatchedFields.DESCRIPTION);

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    @Test
    public void sendsEventWhenChangedFieldGroupIsChecked() {
        when(adminSettingsService.get(WatchedFields.KEY, "")).thenReturn(WatchedFields.OTHER);
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender).send(watcher, "msg");
    }

    @Test
    public void doesNotSendTwiceWhenChannelIsDuplicated() {
        // channels содержит MATTERMOST дважды — LinkedHashSet защищает от двойной отправки
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder()
                        .projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST, NotificationChannel.MATTERMOST))
                        .build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, times(1)).send(watcher, "msg");
    }

    @Test
    public void skipsWatcherIfSettingsDisabled() {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().enabled(false).build());

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void skipsWatcherIfProjectNotInList() {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("OTHER")).build());

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void sendsWhenProjectMatchesWildcard() {
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender).send(watcher, "msg");
    }

    @Test
    public void sendsWhenProjectExplicitlyListed() {
        setupStandardWatcher(List.of("PROJ", "TEST"), List.of(NotificationChannel.MATTERMOST));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender).send(watcher, "msg");
    }

    /**
     * Автор изменения — наблюдатель: он не должен получать уведомление о собственном действии.
     */
    @Test
    public void skipsWatcherIfIsAuthorOfChange() {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));

        service.processEvent(issue, watcher, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    /**
     * Другой наблюдатель получает уведомление, даже если автор тоже наблюдатель.
     */
    @Test
    public void sendsToOtherWatcherWhenAuthorIsAlsoWatcher() {
        ApplicationUser other = new MockApplicationUser("bob");
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher, other));
        when(userSettingsService.getSettings(other))
                .thenReturn(UserSettings.builder()
                        .projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .build());
        when(delegationService.getEffectiveRecipients(other)).thenReturn(List.of(other));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, watcher, NON_EMPTY_DIFF);

        verify(sender, never()).send(eq(watcher), any());
        verify(sender).send(other, "msg");
    }

    @Test
    public void sendsToDelegateInsteadOfWatcher() {
        ApplicationUser delegate = new MockApplicationUser("bob");
        setupStandardWatcher(List.of("*"), List.of());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(delegate));
        when(userSettingsService.getSettings(delegate))
                .thenReturn(UserSettings.builder()
                        .projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .build());
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("delegated");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender).send(delegate, "delegated");
        verify(sender, never()).send(eq(watcher), any());
    }

    /**
     * Делегат с enabled=false не должен получать делегированные уведомления.
     */
    @Test
    public void skipsDeliveryIfDelegateHasNotificationsDisabled() {
        ApplicationUser delegate = new MockApplicationUser("bob");
        setupStandardWatcher(List.of("*"), List.of());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(delegate));
        when(userSettingsService.getSettings(delegate))
                .thenReturn(UserSettings.builder().enabled(false).build());

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void skipsChannelIfAdminDisabled() {
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(false);

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void continuesDeliveryWhenSenderThrows() {
        ApplicationUser watcher2 = new MockApplicationUser("carol");
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher, watcher2));
        UserSettings settings = UserSettings.builder()
                .projects(List.of("*"))
                .channels(List.of(NotificationChannel.MATTERMOST))
                .build();
        when(userSettingsService.getSettings(watcher)).thenReturn(settings);
        when(userSettingsService.getSettings(watcher2)).thenReturn(settings);
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
        when(delegationService.getEffectiveRecipients(watcher2)).thenReturn(List.of(watcher2));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");
        org.mockito.Mockito.doThrow(new RuntimeException("сеть недоступна"))
                .when(sender).send(watcher, "msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender).send(watcher2, "msg");
    }

    /**
     * Если watcher == recipient (нет делегирования) — второй вызов getSettings не делается.
     */
    @Test
    public void doesNotCallGetSettingsTwiceWhenNoDelegate() {
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(userSettingsService, times(1)).getSettings(watcher);
    }

    /**
     * Неактивный пользователь-наблюдатель должен быть пропущен ещё до обращения к его настройкам.
     */
    @Test
    public void skipsInactiveWatcher() {
        ApplicationUser inactive = mock(ApplicationUser.class);
        when(inactive.isActive()).thenReturn(false);
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(inactive));

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(userSettingsService, never()).getSettings(inactive);
        verify(sender, never()).send(any(), any());
    }

    /**
     * Если два наблюдателя делегировали уведомления одному получателю,
     * сообщение должно уйти ровно один раз, а не дважды.
     */
    @Test
    public void sendsOnceWhenTwoWatchersDelegateToSameRecipient() {
        ApplicationUser otherWatcher = new MockApplicationUser("bob");
        ApplicationUser sharedDelegate = new MockApplicationUser("carol");

        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher, otherWatcher));
        UserSettings baseSettings = UserSettings.builder().projects(List.of("*")).channels(List.of()).build();
        when(userSettingsService.getSettings(watcher)).thenReturn(baseSettings);
        when(userSettingsService.getSettings(otherWatcher)).thenReturn(baseSettings);
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(sharedDelegate));
        when(delegationService.getEffectiveRecipients(otherWatcher)).thenReturn(List.of(sharedDelegate));
        when(userSettingsService.getSettings(sharedDelegate))
                .thenReturn(UserSettings.builder()
                        .projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .build());
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, times(1)).send(sharedDelegate, "msg");
    }

    /**
     * Admin-флаги каналов должны читаться один раз на всё событие,
     * независимо от числа получателей.
     */
    @Test
    public void checksAdminChannelFlagsOncePerEvent() {
        ApplicationUser watcher2 = new MockApplicationUser("carol");
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher, watcher2));
        UserSettings settings = UserSettings.builder()
                .projects(List.of("*"))
                .channels(List.of(NotificationChannel.MATTERMOST))
                .build();
        when(userSettingsService.getSettings(watcher)).thenReturn(settings);
        when(userSettingsService.getSettings(watcher2)).thenReturn(settings);
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
        when(delegationService.getEffectiveRecipients(watcher2)).thenReturn(List.of(watcher2));
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(adminSettingsService, times(1)).isChannelEnabled(NotificationChannel.MATTERMOST);
    }

    /** Получателю уведомления о действии второе сообщение об изменении полей не уходит. */
    @Test
    public void excludedRecipientGetsNoFieldChangeMail() {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processEvent(issue, null, NON_EMPTY_DIFF, List.of(watcher));

        verify(sender, never()).send(any(), any());
    }

    // ---- уведомления о действиях ----------------------------------------

    /** Контекстов нет вообще — значит действие нигде не включено, и плагин молчит. */
    @Test
    public void actionIsSkippedWhenNoContextEnablesIt() {
        service.processAction(issue, null, NotificationAction.MENTION, List.of(watcher), Map.of());

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    /** Действие есть в контексте, но выключено — уведомления не уходят. */
    @Test
    public void actionIsSkippedWhenDisabledInContext() {
        ProjectContext context = new ProjectContext(ProjectContexts.DEFAULT_ID, "Остальные проекты",
                Set.of(), Set.of(),
                Map.of(NotificationAction.MENTION.key(), new ProjectContext.ActionSetting(false, "")),
                Map.of());
        when(adminSettingsService.get(ProjectContexts.KEY, ""))
                .thenReturn(ProjectContexts.format(List.of(context)));

        service.processAction(issue, null, NotificationAction.MENTION, List.of(watcher), Map.of());

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void actionRendersTemplateForExplicitRecipients() {
        enableAction(NotificationAction.MENTION, "Упомянули в {issueKey}");
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST)).build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));

        service.processAction(issue, null, NotificationAction.MENTION, List.of(watcher),
                Map.of("issueKey", "PROJ-1"));

        // получатели переданы явно — наблюдателей задачи не спрашиваем
        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender).send(watcher, "Упомянули в PROJ-1");
    }

    @Test
    public void actionFallsBackToWatchersWhenRecipientsEmpty() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1"));

        verify(sender).send(watcher, "Комментарий в PROJ-1");
    }

    /** Запрет администратора переключает сообщение на шаблон без текста комментария. */
    @Test
    public void adminBanOnCommentTextSwitchesTemplate() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий: {comment}");
        when(adminSettingsService.get(
                ActionTemplates.templateKeyNoText(NotificationAction.COMMENT_ADDED, NotificationChannel.MATTERMOST), ""))
                .thenReturn("Новый комментарий в {issueKey}");
        when(adminSettingsService.get(ActionTemplates.HIDE_COMMENT_TEXT_KEY, "false")).thenReturn("true");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1", "comment", "секрет"));

        verify(sender).send(watcher, "Новый комментарий в PROJ-1");
    }

    /** Личная настройка получателя действует, даже если администратор текст разрешил. */
    @Test
    public void userCanHideCommentTextForThemselves() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий: {comment}");
        when(adminSettingsService.get(
                ActionTemplates.templateKeyNoText(NotificationAction.COMMENT_ADDED, NotificationChannel.MATTERMOST), ""))
                .thenReturn("Комментарий в {issueKey}");
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .commentTextHidden(true).build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1", "comment", "секрет"));

        verify(sender).send(watcher, "Комментарий в PROJ-1");
    }

    /**
     * Режим «всегда с текстом»: личная галка получателя не спрашивается — иначе
     * администратор не может гарантировать, что текст дойдёт до всех.
     */
    @Test
    public void shownModeIgnoresUserHiddenFlag() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий: {comment}");
        when(adminSettingsService.get(CommentTextMode.KEY, "")).thenReturn(CommentTextMode.SHOWN.key());
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .commentTextHidden(true).build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1", "comment", "секрет"));

        verify(sender).send(watcher, "Комментарий: секрет");
    }

    /** Режим «всегда без текста» переключает на шаблон без комментария. */
    @Test
    public void hiddenModeSwitchesTemplateEvenIfUserAllowsText() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий: {comment}");
        when(adminSettingsService.get(
                ActionTemplates.templateKeyNoText(NotificationAction.COMMENT_ADDED, NotificationChannel.MATTERMOST), ""))
                .thenReturn("Новый комментарий в {issueKey}");
        when(adminSettingsService.get(CommentTextMode.KEY, "")).thenReturn(CommentTextMode.HIDDEN.key());
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1", "comment", "секрет"));

        verify(sender).send(watcher, "Новый комментарий в PROJ-1");
    }

    /** Текст не должен уехать через шаблон «без текста», даже если в нём оставили {comment}. */
    @Test
    public void noTextTemplateNeverCarriesCommentBody() {
        enableAction(NotificationAction.COMMENT_ADDED, "неважно");
        when(adminSettingsService.get(
                ActionTemplates.templateKeyNoText(NotificationAction.COMMENT_ADDED, NotificationChannel.MATTERMOST), ""))
                .thenReturn("Комментарий: {comment}");
        when(adminSettingsService.get(ActionTemplates.HIDE_COMMENT_TEXT_KEY, "false")).thenReturn("true");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("comment", "секрет"));

        verify(sender).send(watcher, "Комментарий: ");
    }

    /** Список получателей нужен слушателю, чтобы не слать им второе уведомление. */
    @Test
    public void actionReturnsRecipientsItReached() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        assertEquals(List.of(watcher), service.processAction(issue, null,
                NotificationAction.COMMENT_ADDED, List.of(), Map.of()));
    }

    /** Сбой одного канала не должен глушить остальные. */
    @Test
    public void actionContinuesOnOtherChannelWhenOneFails() {
        NotificationSender telegramSender = mock(NotificationSender.class);
        NotificationServiceImpl twoChannels = serviceWithSenders(Map.of(
                NotificationChannel.MATTERMOST, sender,
                NotificationChannel.TELEGRAM, telegramSender));

        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        when(adminSettingsService.isChannelEnabled(NotificationChannel.TELEGRAM)).thenReturn(true);
        setupWatcherWithChannels(List.of(NotificationChannel.TELEGRAM, NotificationChannel.MATTERMOST));
        org.mockito.Mockito.doThrow(new RuntimeException("Telegram недоступен"))
                .when(telegramSender).send(eq(watcher), anyString());

        List<ApplicationUser> notified = twoChannels.processAction(
                issue, null, NotificationAction.COMMENT_ADDED, List.of(), Map.of());

        verify(sender).send(eq(watcher), anyString());
        assertEquals(List.of(watcher), notified);
    }

    /**
     * Упали все каналы — получатель не считается уведомлённым, иначе слушатель
     * исключит его и из рассылки об изменении полей, и он не получит ничего.
     */
    @Test
    public void actionDoesNotMarkRecipientNotifiedWhenDeliveryFails() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        setupWatcherWithChannels(List.of(NotificationChannel.MATTERMOST));
        org.mockito.Mockito.doThrow(new RuntimeException("Mattermost недоступен"))
                .when(sender).send(eq(watcher), anyString());

        List<ApplicationUser> notified = service.processAction(
                issue, null, NotificationAction.COMMENT_ADDED, List.of(), Map.of());

        assertTrue(notified.isEmpty());
    }

    /** Без шаблона сообщение не ушло — значит получателя в результате нет. */
    @Test
    public void actionWithoutTemplateReturnsNobody() {
        enableAction(NotificationAction.CLOSED, "");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        assertTrue(service.processAction(issue, null, NotificationAction.CLOSED, List.of(), Map.of()).isEmpty());
    }

    @Test
    public void actionIsSkippedWhenTemplateIsEmpty() {
        enableAction(NotificationAction.CLOSED, "");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.CLOSED, List.of(), Map.of());

        verify(sender, never()).send(any(), any());
    }

    /**
     * Действие, включённое в чужом контексте, в этом проекте не работает:
     * задача попадёт во встроенный контекст, где оно выключено.
     */
    @Test
    public void actionEnabledInAnotherContextIsSkipped() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        enableActionInContext(NotificationAction.COMMENT_ADDED, "OTHER", "");

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(), Map.of());

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
    }

    @Test
    public void actionWorksInContextOfIssueProject() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        enableActionInContext(NotificationAction.COMMENT_ADDED, "PROJ,OTHER", "");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1"));

        verify(sender).send(watcher, "Комментарий в PROJ-1");
    }

    /**
     * Получателей задаёт контекст: выбран автор задачи, поэтому наблюдателей
     * даже не спрашиваем.
     */
    @Test
    public void actionSendsToRecipientsFromContext() {
        enableAction(NotificationAction.COMMENT_ADDED, "Комментарий в {issueKey}");
        enableActionInContext(NotificationAction.COMMENT_ADDED, "", IssueRecipients.REPORTER);
        MockApplicationUser reporter = new MockApplicationUser("carol", "Carol", "carol@example.com");
        when(issue.getReporter()).thenReturn(reporter);
        when(userSettingsService.getSettings(reporter))
                .thenReturn(UserSettings.builder().projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST)).build());
        when(delegationService.getEffectiveRecipients(reporter)).thenReturn(List.of(reporter));

        service.processAction(issue, null, NotificationAction.COMMENT_ADDED, List.of(),
                Map.of("issueKey", "PROJ-1"));

        verify(sender).send(reporter, "Комментарий в PROJ-1");
        verify(watcherManager, never()).getWatchers(any(), any());
    }

    /** Список проектов в настройках получателя относится только к изменениям задач. */
    @Test
    public void actionIgnoresRecipientProjectFilter() {
        enableAction(NotificationAction.MENTION, "Упомянули в {issueKey}");
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("OTHER"))
                        .channels(List.of(NotificationChannel.MATTERMOST)).build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));

        service.processAction(issue, null, NotificationAction.MENTION, List.of(watcher),
                Map.of("issueKey", "PROJ-1"));

        verify(sender).send(watcher, "Упомянули в PROJ-1");
    }

    // ---- вспомогательные методы ----------------------------------------

    /**
     * Включает действие и задаёт оба шаблона Mattermost — единственного канала в тестах:
     * обычный и на случай, когда текст комментария отправлять нельзя.
     */
    private void enableAction(NotificationAction action, String template) {
        enableActionInContext(action, "", "");
        for (NotificationChannel channel : NotificationChannel.actionChannels()) {
            lenient().when(adminSettingsService.get(
                    ActionTemplates.templateKey(action, channel), "")).thenReturn(template);
            lenient().when(adminSettingsService.get(
                    ActionTemplates.templateKeyNoText(action, channel), "")).thenReturn(template);
        }
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
    }

    /**
     * Включает действие в контексте проектов: во встроенном «Остальные проекты»,
     * если {@code projects} пуст, иначе в контексте с этими проектами. Получатели
     * задаются строкой в формате {@link IssueRecipients}; пустая — по умолчанию.
     */
    private void enableActionInContext(NotificationAction action, String projects, String recipients) {
        Set<String> projectKeys = projects.isEmpty()
                ? Set.of()
                : Set.of(projects.split(","));
        String id = projectKeys.isEmpty() ? ProjectContexts.DEFAULT_ID : "a1b2c3d4";
        ProjectContext context = new ProjectContext(id, "Контекст", projectKeys, Set.of(),
                Map.of(action.key(), new ProjectContext.ActionSetting(true, recipients)),
                Map.of());
        lenient().when(adminSettingsService.get(ProjectContexts.KEY, ""))
                .thenReturn(ProjectContexts.format(List.of(context)));
    }

    /**
     * Делегат прав на задачу не имеет: наблюдателем он не является, а содержимое
     * закрытой задачи иначе уехало бы человеку без доступа к проекту.
     */
    @Test
    public void skipsDelegateWithoutBrowsePermission() {
        MockApplicationUser delegate = new MockApplicationUser("bob", "Bob", "bob@example.com");
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(delegate));
        when(permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, issue, delegate))
                .thenReturn(false);

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    /** Делегирование пережило увольнение делегата. */
    @Test
    public void skipsInactiveDelegate() {
        MockApplicationUser delegate = new MockApplicationUser("bob", "Bob", "bob@example.com");
        delegate.setActive(false);
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(delegate));

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(sender, never()).send(any(), any());
    }

    private NotificationServiceImpl serviceWithSenders(Map<NotificationChannel, NotificationSender> senders) {
        Map<NotificationChannel, MessageFormatter> formatters = new EnumMap<>(NotificationChannel.class);
        formatters.put(NotificationChannel.MATTERMOST, formatter);
        return new NotificationServiceImpl(
                watcherManager, customFieldManager, permissionManager, userSettingsService,
                delegationService, adminSettingsService, formatters, senders);
    }

    private void setupWatcherWithChannels(List<NotificationChannel> channels) {
        setupStandardWatcher(List.of("*"), channels);
    }

    private void setupStandardWatcher(List<String> projects, List<NotificationChannel> channels) {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(projects).channels(channels).build());
        when(delegationService.getEffectiveRecipients(watcher)).thenReturn(List.of(watcher));
    }
}
