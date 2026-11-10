package ru.my.rest;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.sal.api.ApplicationProperties;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.my.ao.NotificationJobEntity;
import ru.my.model.QueuedIssueEvent;
import ru.my.model.DiffResult;
import javax.ws.rs.core.Response;
import java.util.Date;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class FailedNotificationsResourceTest {
    private final ActiveObjects ao = mock(ActiveObjects.class);
    private final JiraAuthenticationContext auth = mock(JiraAuthenticationContext.class);
    private final GlobalPermissionManager globals = mock(GlobalPermissionManager.class);
    private final IssueManager issues = mock(IssueManager.class);
    private final PermissionManager permissions = mock(PermissionManager.class);
    private final ApplicationProperties properties = mock(ApplicationProperties.class);
    private final com.atlassian.jira.issue.comments.CommentManager comments = mock(com.atlassian.jira.issue.comments.CommentManager.class);
    private final com.atlassian.jira.issue.comments.CommentPermissionManager commentPermissions = mock(com.atlassian.jira.issue.comments.CommentPermissionManager.class);
    private final MockApplicationUser admin = new MockApplicationUser("admin");
    private final ru.my.impl.PersistentNotificationQueue queue = mock(ru.my.impl.PersistentNotificationQueue.class);
    private FailedNotificationsResource resource;

    @Before
    public void setUp() {
        resource = new FailedNotificationsResource(ao, auth, globals, issues, permissions, properties, comments, commentPermissions, queue);
    }

    private void admin() {
        when(auth.getLoggedInUser()).thenReturn(admin);
        when(globals.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
    }

    private NotificationJobEntity row(int id, String payload) {
        NotificationJobEntity row = mock(NotificationJobEntity.class);
        when(row.getID()).thenReturn(id);
        when(row.getPayload()).thenReturn(payload);
        return row;
    }

    private FailedNotificationDto.Page page(Response response) {
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity() instanceof FailedNotificationDto.Page);
        return (FailedNotificationDto.Page) response.getEntity();
    }

    @Test
    public void authenticationAndAdminPermissionPrecedeAo() {
        assertEquals(401, resource.get(null).getStatus());
        when(auth.getLoggedInUser()).thenReturn(admin);
        assertEquals(403, resource.get(null).getStatus());
        verifyNoInteractions(ao);
    }

    @Test
    public void rejectsInvalidCursorWithoutQuery() {
        admin();
        for (String cursor : List.of("", "0", "-1", "no", "2147483648")) {
            assertEquals(400, resource.get(cursor).getStatus());
        }
        verifyNoInteractions(ao);
    }

    @Test
    public void scansFiftyRowsAndKeepsCursorEvenWhenNoIssuesAreVisible() {
        admin();
        String payload = new QueuedIssueEvent(42L, null, 2L, false, null, null, false,
                new DiffResult(List.of())).encode();
        NotificationJobEntity[] rows = new NotificationJobEntity[51];
        for (int i = 0; i < rows.length; i++) rows[i] = row(100 - i, payload);
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class))).thenReturn(rows);

        FailedNotificationDto.Page result = page(resource.get("101"));

        assertTrue(result.items.isEmpty());
        assertEquals(Integer.valueOf(51), result.nextBeforeId);
        verify(issues, times(50)).getIssueObject(42L);
        verify(rows[50], never()).getPayload();
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(ao).find(eq(NotificationJobEntity.class), query.capture());
        assertEquals(51, query.getValue().getLimit());
        assertTrue(query.getValue().getWhereClause().contains("ID < ?"));
    }

    @Test
    public void administratorWithoutIssuePermissionCannotSeeMetadataOrError() {
        admin();
        String payload = new QueuedIssueEvent(42L, null, 2L, true, 88L, "SECRET BODY", false,
                new DiffResult(List.of())).encode();
        NotificationJobEntity row = row(1, payload);
        MutableIssue issue = mock(MutableIssue.class);
        when(issues.getIssueObject(42L)).thenReturn(issue);
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class))).thenReturn(new NotificationJobEntity[]{row});

        assertTrue(page(resource.get(null)).items.isEmpty());
        verify(row, never()).getLastError();
        verifyNoInteractions(properties);
    }

    @Test
    public void malformedPayloadOnlyProducesGenericMetadata() {
        admin();
        NotificationJobEntity row = row(1, "SECRET malformed JSON");
        when(row.getLastError()).thenReturn("SECRET ERROR");
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class))).thenReturn(new NotificationJobEntity[]{row});

        FailedNotificationDto item = page(resource.get(null)).items.get(0);

        assertEquals("Некорректное событие", item.event);
        assertEquals("Не удалось прочитать сохранённое событие", item.error);
        assertNull(item.issueId);
        assertNull(item.issueUrl);
        verify(row, never()).getLastError();
        verifyNoInteractions(issues, permissions);
    }

    @Test
    public void accessibleIssueShowsSafeDetailsAndMillisecondTimes() throws Exception {
        admin();
        String payload = new QueuedIssueEvent(42L, "SECRET AUTHOR", 2L, true, 88L, "SECRET BODY", true,
                new DiffResult(List.of(new DiffResult.FieldChange("assignee", "SECRET OLD", "SECRET NEW", "secret-id"),
                        new DiffResult.FieldChange("status", "old", "new"),
                        new DiffResult.FieldChange("summary", "old", "new")))).encode();
        NotificationJobEntity row = row(7, payload);
        when(row.getAttempts()).thenReturn(3);
        when(row.getLastFailedAt()).thenReturn(new Date(1234));
        when(row.getAvailableAt()).thenReturn(new Date(5678));
        MutableIssue issue = mock(MutableIssue.class);
        when(issue.getId()).thenReturn(42L);
        when(issue.getKey()).thenReturn("PROJ-1");
        when(issues.getIssueObject(42L)).thenReturn(issue);
        when(permissions.hasPermission(ProjectPermissions.BROWSE_PROJECTS, issue, admin)).thenReturn(true);
        com.atlassian.jira.issue.comments.Comment comment = mock(com.atlassian.jira.issue.comments.Comment.class);
        when(comments.getCommentById(88L)).thenReturn(comment);
        when(commentPermissions.hasBrowsePermission(admin, comment)).thenReturn(true);
        when(properties.getBaseUrl(any())).thenReturn("https://jira.example");
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class))).thenReturn(new NotificationJobEntity[]{row});

        FailedNotificationDto.Page result = page(resource.get(null));
        FailedNotificationDto item = result.items.get(0);

        assertNull(result.nextBeforeId);
        assertEquals("https://jira.example/browse/PROJ-1", item.issueUrl);
        assertEquals("Комментарий №88; Изменение исполнителя; Изменение статуса; Поле: summary", item.context);
        assertEquals("Подробности появятся после следующей попытки", item.error);
        assertEquals(Long.valueOf(1234), item.lastFailedAt);
        assertEquals(Long.valueOf(5678), item.nextAttemptAt);
        assertEquals(3, item.attempts);
        String json = new org.codehaus.jackson.map.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("SECRET"));
        assertFalse(json.contains("secret-id"));
        assertFalse(json.contains("commentBody"));
        assertFalse(json.contains("payload"));
    }
    @Test
    public void accessibleIssueDoesNotExposeHiddenOrDeletedCommentDetails() {
        admin();
        String payload = new QueuedIssueEvent(42L, null, 2L, true, 88L, "SECRET BODY", true,
                new DiffResult(List.of())).encode();
        NotificationJobEntity row = row(1, payload);
        MutableIssue issue = mock(MutableIssue.class);
        when(issues.getIssueObject(42L)).thenReturn(issue);
        when(permissions.hasPermission(ProjectPermissions.BROWSE_PROJECTS, issue, admin)).thenReturn(true);
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class))).thenReturn(new NotificationJobEntity[]{row});
        com.atlassian.jira.issue.comments.Comment comment = mock(com.atlassian.jira.issue.comments.Comment.class);
        when(comments.getCommentById(88L)).thenReturn(comment).thenReturn(null);

        assertTrue(page(resource.get(null)).items.isEmpty());
        assertTrue(page(resource.get(null)).items.isEmpty());
        verify(commentPermissions).hasBrowsePermission(admin, comment);
        verify(row, never()).getLastError();
        verifyNoInteractions(properties);
    }

    @Test
    public void retryRequiresAdminBeforeAccessingQueue() {
        assertEquals(401, resource.retry(1).getStatus());
        when(auth.getLoggedInUser()).thenReturn(admin);
        assertEquals(403, resource.retry(1).getStatus());
        verifyNoInteractions(queue);
    }

    @Test
    public void retryMapsQueueResultsAndRejectsInvalidId() {
        admin();
        assertEquals(400, resource.retry(0).getStatus());
        verifyNoInteractions(queue);
        when(queue.retryNow(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.SCHEDULED);
        assertEquals(202, resource.retry(1).getStatus());
        when(queue.retryNow(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.NOT_FOUND);
        assertEquals(404, resource.retry(1).getStatus());
        when(queue.retryNow(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.BUSY);
        assertEquals(409, resource.retry(1).getStatus());
    }

    @Test
    public void retryRejectsMalformedOrInaccessibleIssue() {
        admin();
        when(queue.retryNow(eq(1), any())).thenAnswer(call -> {
            java.util.function.Predicate<NotificationJobEntity> visible = call.getArgument(1);
            assertFalse(visible.test(row(1, "malformed")));
            String payload = new QueuedIssueEvent(42L, null, 2L, false, null, null, false,
                    new DiffResult(List.of())).encode();
            assertFalse(visible.test(row(1, payload)));
            return ru.my.impl.PersistentNotificationQueue.RetryResult.NOT_FOUND;
        });
        assertEquals(404, resource.retry(1).getStatus());
    }

    @Test
    public void discardRequiresAdminAndMapsQueueResults() {
        assertEquals(401, resource.discard(1).getStatus());
        when(auth.getLoggedInUser()).thenReturn(admin);
        assertEquals(403, resource.discard(1).getStatus());
        verifyNoInteractions(queue);
        admin();
        assertEquals(400, resource.discard(0).getStatus());
        when(queue.discard(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.NOT_FOUND);
        assertEquals(404, resource.discard(1).getStatus());
        when(queue.discard(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.BUSY);
        assertEquals(409, resource.discard(1).getStatus());
        when(queue.discard(eq(1), any())).thenReturn(ru.my.impl.PersistentNotificationQueue.RetryResult.SCHEDULED);
        assertEquals(204, resource.discard(1).getStatus());
    }

}
