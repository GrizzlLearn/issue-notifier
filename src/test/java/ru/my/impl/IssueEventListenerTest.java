package ru.my.impl;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.ofbiz.core.entity.GenericValue;
import ru.my.api.NotificationService;
import ru.my.model.DiffResult;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
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

    private IssueEventListener listener;

    @Before
    public void setUp() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).submit(any(Runnable.class));

        listener = new IssueEventListener(executor, notificationService);
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

    // ---- вспомогательные методы ----------------------------------------

    /** Событие без changelog — для проверки фильтрации по типу или пустого diff. */
    private IssueEvent emptyEvent(Long typeId) {
        return new IssueEvent(mock(Issue.class), Collections.emptyMap(), null, typeId);
    }

    /** Событие с одним изменённым полем — DiffFormatter вернёт непустой DiffResult. */
    private IssueEvent eventWithChanges(Long typeId) {
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

        return new IssueEvent(mock(Issue.class), null, null, null, changeLog,
                Collections.emptyMap(), typeId);
    }
}
