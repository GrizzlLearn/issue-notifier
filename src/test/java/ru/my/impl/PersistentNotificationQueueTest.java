package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.scheduler.SchedulerService;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import ru.my.ao.NotificationJobEntity;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PersistentNotificationQueueTest {
    private final ActiveObjects ao = mock(ActiveObjects.class);
    private final SchedulerService scheduler = mock(SchedulerService.class);
    private final ClusterLockService locks = mock(ClusterLockService.class);
    private final ClusterLock lock = mock(ClusterLock.class);
    private final ThreadLocalDelegateExecutorFactory delegates = mock(ThreadLocalDelegateExecutorFactory.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final NotificationJobEntity row = mock(NotificationJobEntity.class);
    private final Consumer<String> handler = mock();
    private PersistentNotificationQueue queue;

    @Before
    public void setup() throws Exception {
        when(delegates.createExecutorService(any())).thenReturn(executor);
        when(executor.awaitTermination(anyLong(), any())).thenReturn(true);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
        when(locks.getLockForName(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(row.getID()).thenReturn(1);
        when(row.getPayload()).thenReturn("event");
        when(row.getAvailableAt()).thenReturn(new Date(0));
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class)))
                .thenReturn(new NotificationJobEntity[]{row});
        when(ao.executeInTransaction(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction());
        queue = new PersistentNotificationQueue(ao, scheduler, locks, delegates);
        queue.start(handler);
    }

    @Test
    public void successfulProcessingDeletesPersistentJob() {
        queue.runJob(null);
        verify(handler).accept("event");
        verify(ao).delete(row);
        verify(lock).unlock();
    }

    @Test
    public void rejectionLeavesJobForNextPoll() {
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        queue.runJob(null);
        verifyNoInteractions(handler);
        verify(ao, never()).delete(any(NotificationJobEntity.class));
    }

    @Test
    public void failureRetainsJobAndSchedulesRetryWithoutPersistingExceptionText() {
        doThrow(new IllegalStateException("private body")).when(handler).accept(anyString());
        long before = System.currentTimeMillis();
        queue.runJob(null);
        verify(row).setAttempts(1);
        var available = org.mockito.ArgumentCaptor.forClass(Date.class);
        verify(row).setAvailableAt(available.capture());
        assertTrue(available.getValue().getTime() >= before + 2_000);
        verify(row).save();
        verify(ao, never()).delete(any(NotificationJobEntity.class));
    }

    @Test
    public void newInstanceProcessesPendingRowsFromSameDatabase() {
        queue.stop();
        var restarted = new PersistentNotificationQueue(ao, scheduler, locks, delegates);
        restarted.start(handler);
        restarted.runJob(null);
        verify(handler).accept("event");
        verify(ao).delete(row);
        verify(scheduler, never()).unscheduleJob(any());
    }

    @Test
    public void anotherRunnerHoldingLockPreventsDuplicateProcessing() {
        when(lock.tryLock()).thenReturn(false);
        queue.runJob(null);
        verifyNoInteractions(handler);
        verify(ao, never()).delete(any(NotificationJobEntity.class));
        verify(lock, never()).unlock();
    }

    @Test
    public void interruptionAfterHandlerDoesNotAcknowledgeJob() {
        doAnswer(call -> { Thread.currentThread().interrupt(); return null; }).when(handler).accept(anyString());
        try {
            queue.runJob(null);
            verify(ao, never()).delete(any(NotificationJobEntity.class));
            verify(row, never()).save();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void stopKeepsSharedScheduleAndPendingRows() {
        queue.stop();
        verify(executor).shutdown();
        verify(scheduler).unregisterJobRunner(any());
        verify(scheduler, never()).unscheduleJob(any());
        verify(ao, never()).delete(any(NotificationJobEntity.class));
    }
    @Test
    public void enqueuePersistsPayloadBeforeReturning() {
        queue.enqueue("snapshot");
        verify(ao).executeInTransaction(any());
        verify(ao).create(eq(NotificationJobEntity.class),
                argThat(param -> param.getField().equals("PAYLOAD") && param.getValue().equals("snapshot")),
                argThat(param -> param.getField().equals("AVAILABLE_AT") && param.getValue() instanceof Date),
                argThat(param -> param.getField().equals("ATTEMPTS") && param.getValue().equals(0)));
    }

    @Test
    public void emptyQueueMakesNoWrites() {
        when(ao.find(eq(NotificationJobEntity.class), any(Query.class)))
                .thenReturn(new NotificationJobEntity[0]);
        queue.runJob(null);
        verify(ao, never()).executeInTransaction(any());
        verify(executor, never()).execute(any());
    }

    @Test
    public void repeatedFailuresSaturateAttemptCounterAndCapDelay() {
        when(row.getAttempts()).thenReturn(Integer.MAX_VALUE);
        doThrow(new IllegalStateException()).when(handler).accept(anyString());
        long before = System.currentTimeMillis();
        queue.runJob(null);
        verify(row).setAttempts(Integer.MAX_VALUE);
        var available = org.mockito.ArgumentCaptor.forClass(Date.class);
        verify(row).setAvailableAt(available.capture());
        assertTrue(available.getValue().getTime() >= before + 300_000);
        assertTrue(available.getValue().getTime() <= System.currentTimeMillis() + 300_000);
    }

    @Test
    public void gracefulStopAcknowledgesAlreadyRunningSuccessfulHandler() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopping = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(handler).accept("event");
        doAnswer(call -> { stopping.countDown(); return null; }).when(executor).shutdown();
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var processing = threads.submit(() -> queue.runJob(null));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var stop = threads.submit(queue::stop);
            assertTrue(stopping.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            processing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            stop.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(ao).delete(row);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    public void jobLocksUseBoundedStripesInsteadOfGrowingWithEventIds() {
        when(row.getID()).thenReturn(65);
        queue.runJob(null);
        verify(locks).getLockForName("ru.noname.atlassian.jira.in.notification-stripe.1");
    }

    @Test
    public void saturatedRealPoolKeepsEveryJobUntilItCanBeProcessed() throws Exception {
        ActiveObjects database = mock(ActiveObjects.class);
        ThreadLocalDelegateExecutorFactory realDelegates = mock(ThreadLocalDelegateExecutorFactory.class);
        when(realDelegates.createExecutorService(any())).thenAnswer(call -> call.getArgument(0));
        var pending = new java.util.concurrent.ConcurrentSkipListMap<Integer, NotificationJobEntity>();
        for (int id = 1; id <= 16; id++) {
            NotificationJobEntity job = mock(NotificationJobEntity.class);
            when(job.getID()).thenReturn(id);
            when(job.getPayload()).thenReturn("job-" + id);
            when(job.getAvailableAt()).thenReturn(new Date(0));
            pending.put(id, job);
        }
        when(database.find(eq(NotificationJobEntity.class), any(Query.class))).thenAnswer(call -> {
            Query query = call.getArgument(1);
            if ("ID = ?".equals(query.getWhereClause())) {
                NotificationJobEntity job = pending.get(((Number) query.getWhereParams()[0]).intValue());
                return job == null ? new NotificationJobEntity[0] : new NotificationJobEntity[]{job};
            }
            return pending.values().stream().limit(query.getLimit()).toArray(NotificationJobEntity[]::new);
        });
        when(database.executeInTransaction(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction());
        var completed = new java.util.concurrent.Semaphore(0);
        doAnswer(call -> {
            net.java.ao.RawEntity<?>[] deleted = (net.java.ao.RawEntity<?>[]) call.getRawArguments()[0];
            for (net.java.ao.RawEntity<?> job : deleted) {
                assertNotNull(pending.remove(((NotificationJobEntity) job).getID()));
                completed.release();
            }
            return null;
        }).when(database).delete(any(net.java.ao.RawEntity[].class));
        var busy = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        var realLocks = mock(ClusterLockService.class);
        var stripes = new java.util.concurrent.ConcurrentHashMap<String, ClusterLock>();
        when(realLocks.getLockForName(anyString())).thenAnswer(call -> stripes.computeIfAbsent(call.getArgument(0), name -> {
            var delegate = new java.util.concurrent.locks.ReentrantLock();
            ClusterLock stripe = mock(ClusterLock.class);
            when(stripe.tryLock()).thenAnswer(attempt -> delegate.tryLock());
            doAnswer(unlock -> { delegate.unlock(); return null; }).when(stripe).unlock();
            return stripe;
        }));
        var actual = new PersistentNotificationQueue(database, scheduler, realLocks, realDelegates);
        var delivered = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        actual.start(payload -> {
            busy.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertTrue(delivered.add(payload));
        });
        try {
            actual.runJob(null);
            assertTrue(busy.await(5, java.util.concurrent.TimeUnit.SECONDS));
            for (int poll = 0; poll < 5; poll++) actual.runJob(null);
            assertEquals(16, pending.size());
            verify(database, never()).delete(any(net.java.ao.RawEntity[].class));
            release.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            int acknowledged = 0;
            while (acknowledged < 16 && System.nanoTime() < deadline) {
                actual.runJob(null);
                if (completed.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) acknowledged++;
            }
            assertEquals("delivered=" + delivered + ", db=" + pending.keySet() + ", calls=" + mockingDetails(database).getInvocations(), 16, acknowledged);
            assertTrue(pending.isEmpty());
            assertEquals(16, delivered.size());
        } finally {
            release.countDown();
            actual.stop();
        }
    }

}
