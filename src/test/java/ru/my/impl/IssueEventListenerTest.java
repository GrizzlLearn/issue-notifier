package ru.my.impl;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.sal.api.ApplicationProperties;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.ofbiz.core.entity.GenericValue;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationService;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Проверяет маршрутизацию событий: CREATED и DELETED игнорируются,
 * для остальных diff парсится синхронно и задача передаётся в executor.
 */
@RunWith(MockitoJUnitRunner.class)
public class IssueEventListenerTest {

    @Mock private ExecutorService executor;
    @Mock private NotificationService notificationService;
    @Mock private UserManager userManager;
    @Mock private AdminSettingsService adminSettingsService;
    @Mock private ApplicationProperties applicationProperties;

    private IssueEventListener listener;

    @Before
    public void setUp() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).submit(any(Runnable.class));

        listener = new IssueEventListener(executor, notificationService, userManager,
                applicationProperties, adminSettingsService);
    }

    @Test
    public void ignoresIssueCreatedEvent() {
        IssueEvent event = emptyEvent(EventType.ISSUE_CREATED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any(), any(), any());
    }

    @Test
    public void ignoresIssueDeletedEvent() {
        IssueEvent event = emptyEvent(EventType.ISSUE_DELETED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any(), any(), any());
    }

    @Test
    public void skipsEventWithEmptyChangelog() {
        // changeLog == null → DiffFormatter возвращает пустой DiffResult → задача не отправляется
        IssueEvent event = emptyEvent(EventType.ISSUE_UPDATED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
    }

    @Test
    public void processesIssueUpdatedEvent() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_UPDATED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class));
    }

    @Test
    public void processesIssueAssignedEvent() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_ASSIGNED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class));
    }

    @Test
    public void processesIssueResolvedEvent() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_RESOLVED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class));
    }

    @Test
    public void continuesAfterNotificationServiceThrows() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_UPDATED_ID);
        org.mockito.Mockito.doThrow(new RuntimeException("ошибка"))
                .when(notificationService).processEvent(any(), any(), any());

        listener.onIssueEvent(event);

        // сервис был вызван (исключение внутри — не повод не попробовать)
        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class));
    }

    @Test
    public void doesNotPropagateRejectedExecution() {
        // Симулируем завершённый executor (shutdown race): submit бросает RejectedExecutionException.
        // Listener должен поглотить её и не дойти до notificationService.
        IssueEvent event = eventWithChanges(EventType.ISSUE_UPDATED_ID);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("full"))
                .when(executor).submit(any(Runnable.class));

        listener.onIssueEvent(event);

        verify(notificationService, never()).processEvent(any(), any(), any());
    }

    @Test
    public void commentWithMentionNotifiesMentionedUser() {
        ApplicationUser mentioned = mock(ApplicationUser.class);
        org.mockito.Mockito.when(userManager.getUserByName("ipetrov")).thenReturn(mentioned);

        listener.onIssueEvent(commentEvent("[~ipetrov] посмотри пожалуйста"));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.MENTION), eq(List.of(mentioned)), anyMap());
        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), any(), anyMap());
    }

    @Test
    public void commentWithoutMentionNotifiesWatchers() {
        listener.onIssueEvent(commentEvent("обычный комментарий"));

        // пустой список получателей — сервис рассылает наблюдателям задачи
        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap());
    }

    @Test
    public void unknownMentionFallsBackToCommentAction() {
        org.mockito.Mockito.when(userManager.getUserByName("nobody")).thenReturn(null);

        listener.onIssueEvent(commentEvent("[~nobody] ау"));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap());
    }

    /** Закрывающим считается только статус, выбранный для этого проекта. */
    @Test
    public void statusSelectedForProjectTriggersClosedAction() {
        org.mockito.Mockito.when(adminSettingsService.get(ClosingStatuses.KEY, "")).thenReturn("PROJ:3");

        listener.onIssueEvent(eventWithChanges(EventType.ISSUE_UPDATED_ID, issueWithStatus("3", "PROJ")));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.CLOSED), eq(List.of()), anyMap());
    }

    @Test
    public void otherStatusDoesNotTriggerClosedAction() {
        org.mockito.Mockito.when(adminSettingsService.get(ClosingStatuses.KEY, "")).thenReturn("PROJ:3");

        listener.onIssueEvent(eventWithChanges(EventType.ISSUE_UPDATED_ID, issueWithStatus("10001", "PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.CLOSED), any(), anyMap());
    }

    /** Без выбранных статусов проект уведомлений о закрытии не шлёт — правила по категории нет. */
    @Test
    public void projectWithoutConfiguredStatusesDoesNotTriggerClosedAction() {
        listener.onIssueEvent(eventWithChanges(EventType.ISSUE_UPDATED_ID, issueWithStatus("10001", "PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.CLOSED), any(), anyMap());
    }

    // ---- вспомогательные методы ----------------------------------------

    /** Событие без changelog — для проверки фильтрации по типу или пустого diff. */
    private IssueEvent emptyEvent(Long typeId) {
        return new IssueEvent(mock(Issue.class), Collections.emptyMap(), null, typeId);
    }

    /** Событие с комментарием — changelog пустой, как у реального ISSUE_COMMENTED. */
    private IssueEvent commentEvent(String body) {
        Comment comment = mock(Comment.class);
        org.mockito.Mockito.when(comment.getBody()).thenReturn(body);
        org.mockito.Mockito.when(applicationProperties.getBaseUrl()).thenReturn("https://jira.example.com");

        return new IssueEvent(mock(Issue.class), mock(ApplicationUser.class), comment, null, null,
                Collections.<String, Object>emptyMap(), EventType.ISSUE_COMMENTED_ID);
    }

    /** Задача с заданным статусом и проектом. */
    private Issue issueWithStatus(String statusId, String projectKey) {
        Status status = mock(Status.class);
        org.mockito.Mockito.when(status.getId()).thenReturn(statusId);
        Project project = mock(Project.class);
        org.mockito.Mockito.when(project.getKey()).thenReturn(projectKey);
        Issue issue = mock(Issue.class);
        org.mockito.Mockito.when(issue.getStatus()).thenReturn(status);
        org.mockito.Mockito.when(issue.getProjectObject()).thenReturn(project);
        org.mockito.Mockito.when(applicationProperties.getBaseUrl()).thenReturn("https://jira.example.com");
        return issue;
    }

    /** Событие с одним изменённым полем — DiffFormatter вернёт непустой DiffResult. */
    private IssueEvent eventWithChanges(Long typeId) {
        return eventWithChanges(typeId, mock(Issue.class));
    }

    private IssueEvent eventWithChanges(Long typeId, Issue issue) {
        GenericValue item = mock(GenericValue.class);
        org.mockito.Mockito.when(item.getString("field")).thenReturn("Status");
        org.mockito.Mockito.when(item.getString("oldstring")).thenReturn("Open");
        org.mockito.Mockito.when(item.getString("newstring")).thenReturn("In Progress");

        GenericValue changeLog = mock(GenericValue.class);
        try {
            org.mockito.Mockito.when(changeLog.getRelated("ChildChangeItem")).thenReturn(List.of(item));
        } catch (org.ofbiz.core.entity.GenericEntityException e) {
            throw new RuntimeException(e);
        }

        return new IssueEvent(issue, null, null, null, changeLog,
                Collections.emptyMap(), typeId);
    }
}
