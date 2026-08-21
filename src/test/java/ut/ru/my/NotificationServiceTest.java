package ut.ru.my;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.ofbiz.core.entity.GenericValue;
import ru.my.api.AdminSettingsService;
import ru.my.api.DelegationService;
import ru.my.api.MessageFormatter;
import ru.my.api.NotificationSender;
import ru.my.api.UserSettingsService;
import ru.my.impl.NotificationServiceImpl;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет оркестрацию: фильтрацию наблюдателей, делегирование,
 * выбор каналов и изоляцию ошибок отправщика.
 * Все тесты используют пакетный конструктор, принимающий готовые моки.
 */
@RunWith(MockitoJUnitRunner.class)
public class NotificationServiceTest {

    @Mock private com.atlassian.jira.issue.watchers.WatcherManager watcherManager;
    @Mock private UserSettingsService userSettingsService;
    @Mock private DelegationService delegationService;
    @Mock private AdminSettingsService adminSettingsService;
    @Mock private MessageFormatter formatter;
    @Mock private NotificationSender sender;

    private NotificationServiceImpl service;
    private ApplicationUser watcher;
    private Issue issue;

    @Before
    public void setUp() {
        when(formatter.channel()).thenReturn(NotificationChannel.MATTERMOST);
        when(sender.channel()).thenReturn(NotificationChannel.MATTERMOST);

        Map<NotificationChannel, MessageFormatter> formatters = new EnumMap<>(NotificationChannel.class);
        formatters.put(NotificationChannel.MATTERMOST, formatter);
        Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
        senders.put(NotificationChannel.MATTERMOST, sender);

        service = new NotificationServiceImpl(
                watcherManager, userSettingsService, delegationService,
                adminSettingsService, formatters, senders);

        watcher = new MockApplicationUser("alice");

        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJ");
        issue = mock(Issue.class);
        when(issue.getProjectObject()).thenReturn(project);
    }

    @Test
    public void skipsEventWithEmptyChangelog() {
        IssueEvent event = eventWithChangeLog(null);

        service.processEvent(event);

        verify(watcherManager, never()).getWatchers(any(), any());
    }

    @Test
    public void skipsWatcherIfSettingsDisabled() {
        IssueEvent event = eventWithChanges();
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().enabled(false).build());

        service.processEvent(event);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void skipsWatcherIfProjectNotInList() {
        IssueEvent event = eventWithChanges();
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(List.of("OTHER")).build());

        service.processEvent(event);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void sendsWhenProjectMatchesWildcard() {
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        IssueEvent event = eventWithChanges();
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(event);

        verify(sender).send(watcher, "msg");
    }

    @Test
    public void sendsWhenProjectExplicitlyListed() {
        setupStandardWatcher(List.of("PROJ", "TEST"), List.of(NotificationChannel.MATTERMOST));
        IssueEvent event = eventWithChanges();
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("msg");

        service.processEvent(event);

        verify(sender).send(watcher, "msg");
    }

    @Test
    public void sendsToDelegateInsteadOfWatcher() {
        ApplicationUser delegate = new MockApplicationUser("bob");
        setupStandardWatcher(List.of("*"), List.of()); // у watcher нет каналов
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(delegate);
        // у делегата есть Mattermost
        when(userSettingsService.getSettings(delegate))
                .thenReturn(UserSettings.builder()
                        .projects(List.of("*"))
                        .channels(List.of(NotificationChannel.MATTERMOST))
                        .build());
        IssueEvent event = eventWithChanges();
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(true);
        when(formatter.format(any(), any())).thenReturn("delegated");

        service.processEvent(event);

        verify(sender).send(delegate, "delegated");
        verify(sender, never()).send(watcher, any());
    }

    @Test
    public void skipsChannelIfAdminDisabled() {
        setupStandardWatcher(List.of("*"), List.of(NotificationChannel.MATTERMOST));
        IssueEvent event = eventWithChanges();
        when(adminSettingsService.isChannelEnabled(NotificationChannel.MATTERMOST)).thenReturn(false);

        service.processEvent(event);

        verify(sender, never()).send(any(), any());
    }

    @Test
    public void continuesDeliveryWhenSenderThrows() {
        ApplicationUser watcher2 = new MockApplicationUser("carol");

        // оба наблюдателя с Mattermost
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

        // первый отправщик бросает исключение
        org.mockito.Mockito.doThrow(new RuntimeException("сеть недоступна"))
                .when(sender).send(watcher, "msg");

        IssueEvent event = eventWithChanges();
        service.processEvent(event);

        // второй получатель всё равно должен получить уведомление
        verify(sender).send(watcher2, "msg");
    }

    // ---- вспомогательные методы ----------------------------------------

    /**
     * Событие без changelog — DiffFormatter вернёт пустой DiffResult.
     */
    private IssueEvent eventWithChangeLog(GenericValue changeLog) {
        IssueEvent event = mock(IssueEvent.class);
        when(event.getChangeLog()).thenReturn(changeLog);
        when(event.getIssue()).thenReturn(issue);
        return event;
    }

    /**
     * Событие с одним изменённым полем "Status".
     */
    private IssueEvent eventWithChanges() {
        GenericValue item = mock(GenericValue.class);
        when(item.getString("field")).thenReturn("Status");
        when(item.getString("oldstring")).thenReturn("Open");
        when(item.getString("newstring")).thenReturn("In Progress");

        GenericValue changeLog = mock(GenericValue.class);
        try {
            when(changeLog.getRelated("ChildChangeItem")).thenReturn(List.of(item));
        } catch (org.ofbiz.core.entity.GenericEntityException e) {
            throw new RuntimeException(e);
        }

        return eventWithChangeLog(changeLog);
    }

    /**
     * Настраивает watcher с заданными проектами и каналами без делегирования.
     */
    private void setupStandardWatcher(List<String> projects, List<NotificationChannel> channels) {
        when(watcherManager.getWatchers(issue, Locale.ROOT)).thenReturn(List.of(watcher));
        when(userSettingsService.getSettings(watcher))
                .thenReturn(UserSettings.builder().projects(projects).channels(channels).build());
        when(delegationService.getEffectiveRecipient(watcher)).thenReturn(watcher);
    }
}
