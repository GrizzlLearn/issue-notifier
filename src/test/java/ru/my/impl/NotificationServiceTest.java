package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.watchers.WatcherManager;
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
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет оркестрацию: фильтрацию наблюдателей, делегирование,
 * выбор каналов, исключение автора события и изоляцию ошибок отправщика.
 */
@RunWith(MockitoJUnitRunner.class)
public class NotificationServiceTest {

    @Mock private WatcherManager watcherManager;
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

        service = new NotificationServiceImpl(
                watcherManager, userSettingsService, delegationService,
                adminSettingsService, formatters, senders);

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
                watcherManager, userSettingsService, delegationService, adminSettingsService,
                Map.of(), Map.of());

        emptyService.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(watcherManager, never()).getWatchers(any(), any());
        verify(sender, never()).send(any(), any());
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(watcher);
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
        when(delegationService.getEffectiveRecipient(other)).thenReturn(other);
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(delegate);
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(delegate);
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(watcher);
        when(delegationService.getEffectiveRecipient(watcher2)).thenReturn(watcher2);
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(sharedDelegate);
        when(delegationService.getEffectiveRecipient(otherWatcher)).thenReturn(sharedDelegate);
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
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(watcher);
        when(delegationService.getEffectiveRecipient(watcher2)).thenReturn(watcher2);
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(issue, null, NON_EMPTY_DIFF);

        verify(adminSettingsService, times(1)).isChannelEnabled(NotificationChannel.MATTERMOST);
    }

    // ---- вспомогательные методы ----------------------------------------

    private void setupStandardWatcher(List<String> projects, List<NotificationChannel> channels) {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(projects).channels(channels).build());
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(watcher);
    }
}
