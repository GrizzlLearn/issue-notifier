package ru.my.impl;

import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.NotificationService;

import java.util.Collections;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Проверяет маршрутизацию событий: CREATED и DELETED игнорируются,
 * все остальные передаются в NotificationService через executor.
 */
@RunWith(MockitoJUnitRunner.class)
public class IssueEventListenerTest {

    @Mock private ExecutorService executor;
    @Mock private NotificationService notificationService;

    private IssueEventListener listener;

    @Before
    public void setUp() {
        // синхронное выполнение задачи: runnable.run() прямо в тесте
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).submit(any(Runnable.class));

        listener = new IssueEventListener(executor, notificationService);
    }

    @Test
    public void ignoresIssueCreatedEvent() {
        IssueEvent event = eventWithType(EventType.ISSUE_CREATED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any());
    }

    @Test
    public void ignoresIssueDeletedEvent() {
        IssueEvent event = eventWithType(EventType.ISSUE_DELETED_ID);

        listener.onIssueEvent(event);

        verify(executor, never()).submit(any(Runnable.class));
        verify(notificationService, never()).processEvent(any());
    }

    @Test
    public void processesIssueUpdatedEvent() {
        IssueEvent event = eventWithType(EventType.ISSUE_UPDATED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(event);
    }

    @Test
    public void processesIssueAssignedEvent() {
        IssueEvent event = eventWithType(EventType.ISSUE_ASSIGNED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(event);
    }

    @Test
    public void processesIssueResolvedEvent() {
        IssueEvent event = eventWithType(EventType.ISSUE_RESOLVED_ID);

        listener.onIssueEvent(event);

        verify(notificationService).processEvent(event);
    }

    @Test
    public void continuesAfterNotificationServiceThrows() {
        IssueEvent event = eventWithType(EventType.ISSUE_UPDATED_ID);
        org.mockito.Mockito.doThrow(new RuntimeException("ошибка"))
                .when(notificationService).processEvent(event);

        // не должно пробрасывать исключение наружу
        listener.onIssueEvent(event);
    }

    // ---- вспомогательные методы ----------------------------------------

    /**
     * Создаём реальный IssueEvent через конструктор — IssueEvent финальный класс,
     * Mockito не может его мокировать без инлайн-расширения.
     */
    private IssueEvent eventWithType(Long typeId) {
        return new IssueEvent(mock(Issue.class), Collections.emptyMap(), null, typeId);
    }
}
