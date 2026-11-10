package ru.my.impl;

import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.sal.api.ApplicationProperties;
import org.junit.Test;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationService;
import ru.my.model.DiffResult;
import ru.my.model.QueuedIssueEvent;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;

/** Восстановление сохранённого события не зависит от объектов исходного Jira-события. */
public class IssueEventListenerPoolTest {
    @Test
    public void restoresEventAfterRestartAndPropagatesFailureForRetry() {
        IssueManager issues = mock(IssueManager.class);
        UserManager users = mock(UserManager.class);
        NotificationService service = mock(NotificationService.class);
        MutableIssue issue = mock(MutableIssue.class);
        when(issues.getIssueObject(42L)).thenReturn(issue);
        IssueEventListener listener = new IssueEventListener(mock(EventPublisher.class),
                mock(PersistentNotificationQueue.class), issues, users, mock(ApplicationProperties.class),
                mock(JsonEntityPropertyManager.class), service, mock(AdminSettingsService.class));
        String payload = new QueuedIssueEvent(42L, "deleted-author", 2L, false, null, null, false,
                new DiffResult(List.of(new DiffResult.FieldChange("summary", "old", "new")))).encode();
        doThrow(new IllegalStateException("transport failed")).when(service)
                .processEvent(eq(issue), isNull(), any(), any());
        assertThrows(IllegalStateException.class, () -> listener.processQueuedEvent(payload));
        verify(users).getUserByKey("deleted-author");
        verify(service).processEvent(eq(issue), isNull(), any(), any());
    }

    @Test
    public void deletedIssueIsTerminalAndDoesNotSend() {
        IssueEventListener listener = new IssueEventListener(mock(EventPublisher.class),
                mock(PersistentNotificationQueue.class), mock(IssueManager.class), mock(UserManager.class),
                mock(ApplicationProperties.class), mock(JsonEntityPropertyManager.class),
                mock(NotificationService.class), mock(AdminSettingsService.class));
        listener.processQueuedEvent(new QueuedIssueEvent(42L, null, 2L, false, null, null,
                false, new DiffResult(List.of())).encode());
    }
    @Test
    public void capturesCommentAndDiffInOneDurableRecord() throws Exception {
        PersistentNotificationQueue queue = mock(PersistentNotificationQueue.class);
        IssueEventListener listener = new IssueEventListener(mock(EventPublisher.class), queue,
                mock(IssueManager.class), mock(UserManager.class), mock(ApplicationProperties.class),
                mock(JsonEntityPropertyManager.class), mock(NotificationService.class), mock(AdminSettingsService.class));
        MutableIssue issue = mock(MutableIssue.class);
        when(issue.getId()).thenReturn(42L);
        com.atlassian.jira.issue.comments.Comment comment = mock(com.atlassian.jira.issue.comments.Comment.class);
        String body = "x".repeat(600) + " [~tail]";
        when(comment.getBody()).thenReturn(body);
        when(comment.getId()).thenReturn(88L);
        when(comment.getRoleLevelId()).thenReturn(null);
        org.ofbiz.core.entity.GenericValue change = mock(org.ofbiz.core.entity.GenericValue.class);
        when(change.getString("field")).thenReturn("assignee");
        when(change.getString("newvalue")).thenReturn("original-assignee");
        org.ofbiz.core.entity.GenericValue log = mock(org.ofbiz.core.entity.GenericValue.class);
        when(log.getRelated("ChildChangeItem")).thenReturn(List.of(change));

        listener.onIssueEvent(new com.atlassian.jira.event.issue.IssueEvent(issue, null, comment, null, log,
                java.util.Collections.emptyMap(), com.atlassian.jira.event.type.EventType.ISSUE_COMMENTED_ID));

        org.mockito.ArgumentCaptor<String> captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(queue).enqueue(captured.capture());
        QueuedIssueEvent snapshot = QueuedIssueEvent.decode(captured.getValue());
        assertEquals(body, snapshot.commentBody());
        assertEquals(Long.valueOf(88), snapshot.commentId());
        assertEquals("original-assignee", snapshot.diff().getChanges().get(0).toId());
    }

    @Test
    public void mentionFailureStillAttemptsOtherCommentRecipients() {
        IssueManager issues = mock(IssueManager.class);
        UserManager users = mock(UserManager.class);
        NotificationService service = mock(NotificationService.class);
        MutableIssue issue = mock(MutableIssue.class);
        when(issues.getIssueObject(42L)).thenReturn(issue);
        com.atlassian.jira.user.ApplicationUser mentioned = mock(com.atlassian.jira.user.ApplicationUser.class);
        when(users.getUserByName("tail")).thenReturn(mentioned);
        doThrow(new IllegalStateException("transport")).when(service).processAction(any(), any(),
                eq(ru.my.model.NotificationAction.MENTION), any(), anyMap(), any(), any());
        IssueEventListener listener = new IssueEventListener(mock(EventPublisher.class),
                mock(PersistentNotificationQueue.class), issues, users, mock(ApplicationProperties.class),
                mock(JsonEntityPropertyManager.class), service, mock(AdminSettingsService.class));
        String payload = new QueuedIssueEvent(42L, null, 6L, true, null,
                "x".repeat(600) + " [~tail]", false, new DiffResult(List.of())).encode();

        assertThrows(IllegalStateException.class, () -> listener.processQueuedEvent(payload));
        verify(service).processAction(any(), any(), eq(ru.my.model.NotificationAction.COMMENT_ADDED),
                any(), anyMap(), any(), any());
    }

}
