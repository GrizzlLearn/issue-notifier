package ru.my.impl;

import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Слушатель на настоящем пуле потоков, который собирает рабочий конструктор:
 * остальные тесты подменяют executor моком, выполняющим задачи синхронно, и
 * поэтому не видят ни асинхронности, ни переполнения очереди, ни отправки
 * события в уже остановленный пул.
 * <p>
 * Фабрика возвращает переданный ей executor как есть — так под тестом оказывается
 * ровно тот пул, который плагин создаёт в рабочем режиме (core=2, очередь 1000,
 * переполнение отбрасывается).
 */
@RunWith(MockitoJUnitRunner.class)
public class IssueEventListenerPoolTest {

    private static final int QUEUE_CAPACITY = 1000;
    private static final int CORE_POOL_SIZE = 2;

    /**
     * Потоки сверх core создаются, только когда очередь заполнена целиком —
     * так устроен {@code ThreadPoolExecutor} с ограниченной {@code LinkedBlockingQueue}.
     * При переполнении это и происходит, поэтому предел принятых задач считается
     * от максимума пула, а не от core.
     */
    private static final int MAX_POOL_SIZE = 4;

    @Mock private EventPublisher eventPublisher;
    @Mock private ThreadLocalDelegateExecutorFactory executorFactory;
    @Mock private NotificationService notificationService;
    @Mock private UserManager userManager;
    @Mock private ApplicationProperties applicationProperties;
    @Mock private AdminSettingsService adminSettingsService;
    @Mock private JsonEntityPropertyManager entityProperties;

    private IssueEventListener listener;

    @Before
    public void setUp() {
        when(executorFactory.createExecutorService(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(applicationProperties.getBaseUrl(any())).thenReturn("https://jira.example.com");
        listener = new IssueEventListener(eventPublisher, executorFactory, userManager,
                applicationProperties, entityProperties, notificationService, adminSettingsService);
    }

    @After
    public void tearDown() {
        listener.destroy();
    }

    /** Задача уходит в другой поток: поток события Jira ждать рассылку не должен. */
    @Test
    public void handlesEventInWorkerThread() throws Exception {
        CountDownLatch handled = new CountDownLatch(1);
        AtomicInteger jiraThreads = new AtomicInteger();
        String eventThread = Thread.currentThread().getName();
        when(notificationService.processAction(any(), any(), any(), any(), anyMap(), any(), any()))
                .thenAnswer(inv -> {
                    if (Thread.currentThread().getName().equals(eventThread)) {
                        jiraThreads.incrementAndGet();
                    }
                    handled.countDown();
                    return List.of();
                });

        listener.onIssueEvent(commentEvent());

        assertTrue("рассылка не выполнилась", handled.await(5, TimeUnit.SECONDS));
        assertEquals("рассылка выполнена в потоке события Jira", 0, jiraThreads.get());
    }

    /**
     * Очередь переполнена: события сверх предела отбрасываются, но ошибка не уходит
     * в диспетчер событий Jira — иначе сбойный поток уведомлений ломал бы работу
     * других слушателей. Раньше это проверялось моком, который бросал
     * {@code RejectedExecutionException}, чего настоящий пул при переполнении не делает.
     */
    @Test
    public void overflowingQueueDoesNotBreakEventDispatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workersBusy = new CountDownLatch(CORE_POOL_SIZE);
        AtomicInteger handled = new AtomicInteger();
        when(notificationService.processAction(any(), any(), any(), any(), anyMap(), any(), any()))
                .thenAnswer(inv -> {
                    workersBusy.countDown();
                    // таймаут — сбой самого теста: иначе воркер досчитал бы
                    // обработанные события и тест позеленел бы зря
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("воркер не дождался разблокировки");
                    }
                    handled.incrementAndGet();
                    return List.of();
                });

        IssueEvent event = commentEvent();
        int submitted = QUEUE_CAPACITY + MAX_POOL_SIZE + 50;
        for (int i = 0; i < submitted; i++) {
            listener.onIssueEvent(event);   // ни один вызов не должен бросить
        }

        assertTrue("воркеры не взяли задачи", workersBusy.await(5, TimeUnit.SECONDS));
        release.countDown();
        listener.destroy();
        assertTrue("часть событий должна быть обработана", handled.get() > 0);
        // больше, чем очередь плюс занятые воркеры, пул принять не мог — значит
        // лишние события действительно отброшены, а не потеряны молча в тесте
        assertTrue("переполнения не случилось, тест ничего не проверил",
                handled.get() <= QUEUE_CAPACITY + MAX_POOL_SIZE);
        assertTrue("часть событий должна быть отброшена", handled.get() < submitted);
    }

    /** Плагин выгружают: событие, пришедшее после остановки пула, не должно падать. */
    @Test
    public void eventAfterShutdownIsDiscarded() {
        listener.destroy();

        listener.onIssueEvent(commentEvent());   // RejectedExecutionException наружу не идёт
    }

    private IssueEvent commentEvent() {
        Comment comment = mock(Comment.class);
        lenient().when(comment.getBody()).thenReturn("текст комментария");
        lenient().when(comment.getRoleLevelId()).thenReturn(null);
        return new IssueEvent(mock(Issue.class), mock(ApplicationUser.class), comment, null, null,
                Collections.<String, Object>emptyMap(), EventType.ISSUE_COMMENTED_ID);
    }
}
