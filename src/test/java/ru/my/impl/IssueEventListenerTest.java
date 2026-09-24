package ru.my.impl;

import com.atlassian.jira.entity.property.EntityProperty;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
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
import org.mockito.ArgumentCaptor;
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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import ru.my.model.ClosingStatuses;

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
    @Mock private JsonEntityPropertyManager entityProperties;

    private IssueEventListener listener;

    @Before
    public void setUp() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).submit(any(Runnable.class));

        listener = new IssueEventListener(executor, notificationService, userManager,
                applicationProperties, adminSettingsService, entityProperties);
    }

    @Test
    public void ignoresIssueCreatedEvent() {
        IssueEvent event = emptyEvent(EventType.ISSUE_CREATED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any(), any(), any(), any());
    }

    @Test
    public void ignoresIssueDeletedEvent() {
        IssueEvent event = emptyEvent(EventType.ISSUE_DELETED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any(), any(), any(), any());
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

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class), any());
    }

    @Test
    public void processesIssueAssignedEvent() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_ASSIGNED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class), any());
    }

    @Test
    public void processesIssueResolvedEvent() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_RESOLVED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class), any());
    }

    @Test
    public void continuesAfterNotificationServiceThrows() {
        IssueEvent event = eventWithChanges(EventType.ISSUE_UPDATED_ID);
        org.mockito.Mockito.doThrow(new RuntimeException("ошибка"))
                .when(notificationService).processEvent(any(), any(), any(), any());

        listener.onIssueEvent(event);

        // сервис был вызван (исключение внутри — не повод не попробовать)
        verify(notificationService).processEvent(any(Issue.class), isNull(), any(DiffResult.class), any());
    }

    @Test
    public void doesNotPropagateRejectedExecution() {
        // Симулируем завершённый executor (shutdown race): submit бросает RejectedExecutionException.
        // Listener должен поглотить её и не дойти до notificationService.
        IssueEvent event = eventWithChanges(EventType.ISSUE_UPDATED_ID);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("full"))
                .when(executor).submit(any(Runnable.class));

        listener.onIssueEvent(event);

        verify(notificationService, never()).processEvent(any(), any(), any(), any());
    }

    @Test
    public void commentWithMentionNotifiesMentionedUser() {
        ApplicationUser mentioned = mock(ApplicationUser.class);
        org.mockito.Mockito.when(userManager.getUserByName("ipetrov")).thenReturn(mentioned);

        org.mockito.Mockito.when(notificationService.processAction(
                        any(), any(), eq(NotificationAction.MENTION), any(), anyMap()))
                .thenReturn(List.of(mentioned));

        listener.onIssueEvent(commentEvent("[~ipetrov] посмотри пожалуйста"));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.MENTION), eq(List.of(mentioned)), anyMap());
        // упомянутый уже уведомлён — в рассылке о комментарии он исключён
        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap(),
                eq(List.of(mentioned)));
    }

    @Test
    public void commentWithoutMentionNotifiesWatchers() {
        listener.onIssueEvent(commentEvent("обычный комментарий"));

        // пустой список получателей — сервис берёт их из настройки действия
        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap(), eq(List.of()));
    }

    @Test
    public void unknownMentionFallsBackToCommentAction() {
        org.mockito.Mockito.when(userManager.getUserByName("nobody")).thenReturn(null);

        listener.onIssueEvent(commentEvent("[~nobody] ау"));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap(), eq(List.of()));
    }

    /** Внутренний комментарий Service Desk виден только команде — уведомляем исполнителя. */
    @Test
    public void serviceDeskInternalCommentNotifiesAssigneeOnly() {
        ApplicationUser assignee = mock(ApplicationUser.class);
        EntityProperty property = mock(EntityProperty.class);
        org.mockito.Mockito.when(property.getValue()).thenReturn("{\"internal\": true}");
        org.mockito.Mockito.when(entityProperties.get("CommentProperty", 42L, "sd.public.comment"))
                .thenReturn(property);

        listener.onIssueEvent(commentEvent("внутренняя заметка", 42L, assignee));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of(assignee)), anyMap());
        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), anyMap(), any());
    }

    /** Комментарий с ограничением по группе уходит всем, но без текста. */
    @Test
    public void commentRestrictedByGroupGoesWithoutText() {
        Comment comment = mock(Comment.class);
        org.mockito.Mockito.when(comment.getBody()).thenReturn("секрет");
        org.mockito.Mockito.when(comment.getGroupLevel()).thenReturn("jira-developers");
        org.mockito.Mockito.when(applicationProperties.getBaseUrl()).thenReturn("https://jira.example.com");

        listener.onIssueEvent(new IssueEvent(mock(Issue.class), mock(ApplicationUser.class), comment, null, null,
                Collections.<String, Object>emptyMap(), EventType.ISSUE_COMMENTED_ID));

        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.COMMENT_ADDED), eq(List.of()), values.capture(), any());
        assertFalse(values.getValue().containsKey("comment"));
    }

    /** Исправленная опечатка не должна уходить как новый комментарий. */
    @Test
    public void editedCommentIsIgnored() {
        Comment comment = mock(Comment.class);

        listener.onIssueEvent(new IssueEvent(mock(Issue.class), mock(ApplicationUser.class), comment, null, null,
                Collections.<String, Object>emptyMap(), EventType.ISSUE_COMMENT_EDITED_ID));

        verify(notificationService, never()).processAction(any(), any(), any(), any(), anyMap());
        verify(notificationService, never()).processAction(any(), any(), any(), any(), anyMap(), any());
    }

    /** Закрывающим считается только статус, выбранный для этого проекта. */
    @Test
    public void statusSelectedForProjectTriggersClosedAction() {
        org.mockito.Mockito.when(adminSettingsService.get(ClosingStatuses.KEY, "")).thenReturn("PROJ:3");

        listener.onIssueEvent(statusChangedTo("3", issueInProject("PROJ")));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.CLOSED), eq(List.of()), anyMap());
    }

    @Test
    public void otherStatusDoesNotTriggerClosedAction() {
        org.mockito.Mockito.when(adminSettingsService.get(ClosingStatuses.KEY, "")).thenReturn("PROJ:3");

        listener.onIssueEvent(statusChangedTo("10001", issueInProject("PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.CLOSED), any(), anyMap());
    }

    /** Без выбранных статусов проект уведомлений о закрытии не шлёт — правила по категории нет. */
    @Test
    public void projectWithoutConfiguredStatusesDoesNotTriggerClosedAction() {
        listener.onIssueEvent(statusChangedTo("10001", issueInProject("PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.CLOSED), any(), anyMap());
    }

    @Test
    public void assigneeChangeNotifiesNewAssignee() {
        ApplicationUser assignee = mock(ApplicationUser.class);
        org.mockito.Mockito.when(assignee.getDisplayName()).thenReturn("Пётр");
        org.mockito.Mockito.when(userManager.getUserByKey("petr")).thenReturn(assignee);

        listener.onIssueEvent(assignedTo("petr", issueInProject("PROJ")));

        verify(notificationService).processAction(
                any(), any(), eq(NotificationAction.ASSIGNED), eq(List.of(assignee)), anyMap());
    }

    /** Снятие исполнителя уведомлять некому: в changelog пустое newvalue. */
    @Test
    public void clearedAssigneeNotifiesNobody() {
        listener.onIssueEvent(assignedTo(null, issueInProject("PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.ASSIGNED), any(), anyMap());
    }

    /** Назначенный получает одно сообщение: про назначение, а не ещё и про изменение полей. */
    @Test
    public void assigneeIsExcludedFromFieldChangeMail() {
        ApplicationUser assignee = mock(ApplicationUser.class);
        org.mockito.Mockito.when(userManager.getUserByKey("petr")).thenReturn(assignee);
        org.mockito.Mockito.when(notificationService.processAction(
                        any(), any(), eq(NotificationAction.ASSIGNED), any(), anyMap()))
                .thenReturn(List.of(assignee));

        listener.onIssueEvent(assignedTo("petr", issueInProject("PROJ")));

        verify(notificationService).processEvent(
                any(Issue.class), isNull(), any(DiffResult.class), eq(List.of(assignee)));
    }

    @Test
    public void statusChangeDoesNotTriggerAssignedAction() {
        listener.onIssueEvent(statusChangedTo("10001", issueInProject("PROJ")));

        verify(notificationService, never()).processAction(
                any(), any(), eq(NotificationAction.ASSIGNED), any(), anyMap());
    }

    // ---- вспомогательные методы ----------------------------------------

    /** Событие без changelog — для проверки фильтрации по типу или пустого diff. */
    private IssueEvent emptyEvent(Long typeId) {
        return new IssueEvent(mock(Issue.class), Collections.emptyMap(), null, typeId);
    }

    /** Событие с комментарием — changelog пустой, как у реального ISSUE_COMMENTED. */
    private IssueEvent commentEvent(String body) {
        return commentEvent(body, null, null);
    }

    private IssueEvent commentEvent(String body, Long commentId, ApplicationUser assignee) {
        Comment comment = mock(Comment.class);
        org.mockito.Mockito.when(comment.getBody()).thenReturn(body);
        if (commentId != null) {
            org.mockito.Mockito.when(comment.getId()).thenReturn(commentId);
        }
        Issue issue = mock(Issue.class);
        if (assignee != null) {
            org.mockito.Mockito.when(issue.getAssignee()).thenReturn(assignee);
        }
        org.mockito.Mockito.when(applicationProperties.getBaseUrl()).thenReturn("https://jira.example.com");

        return new IssueEvent(issue, mock(ApplicationUser.class), comment, null, null,
                Collections.<String, Object>emptyMap(), EventType.ISSUE_COMMENTED_ID);
    }

    /** Задача с заданным статусом и проектом. */
    private Issue issueInProject(String projectKey) {
        Project project = mock(Project.class);
        org.mockito.Mockito.when(project.getKey()).thenReturn(projectKey);
        Issue issue = mock(Issue.class);
        org.mockito.Mockito.when(issue.getProjectObject()).thenReturn(project);
        org.mockito.Mockito.when(applicationProperties.getBaseUrl()).thenReturn("https://jira.example.com");
        return issue;
    }

    /** Событие с одним изменённым полем — DiffFormatter вернёт непустой DiffResult. */
    private IssueEvent eventWithChanges(Long typeId) {
        return eventWithChanges(typeId, mock(Issue.class));
    }

    private IssueEvent eventWithChanges(Long typeId, Issue issue) {
        return eventWithChanges(typeId, issue, "Status", null);
    }

    /** Изменение статуса с id нового статуса в {@code newvalue}. */
    private IssueEvent statusChangedTo(String statusId, Issue issue) {
        return eventWithChanges(typeId(), issue, "Status", statusId);
    }

    /** Изменение исполнителя с ключом нового исполнителя в {@code newvalue}. */
    private IssueEvent assignedTo(String userKey, Issue issue) {
        return eventWithChanges(typeId(), issue, "assignee", userKey);
    }

    private static Long typeId() {
        return EventType.ISSUE_UPDATED_ID;
    }

    private IssueEvent eventWithChanges(Long typeId, Issue issue, String fieldName, String newValue) {
        GenericValue item = mock(GenericValue.class);
        org.mockito.Mockito.when(item.getString("field")).thenReturn(fieldName);
        org.mockito.Mockito.when(item.getString("oldstring")).thenReturn("Open");
        org.mockito.Mockito.when(item.getString("newstring")).thenReturn("In Progress");
        org.mockito.Mockito.when(item.getString("newvalue")).thenReturn(newValue);

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
