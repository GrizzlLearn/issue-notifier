package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.ao.NotificationJobEntity;
import ru.my.impl.util.NotificationErrors;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import ru.my.model.DeliveryProgress;

/**
 * Очередь событий в AO с доставкой как минимум один раз. Падение после отправки,
 * но до удаления строки может дать повтор; ожидающие задания в памяти не хранятся.
 */
@Named
public class PersistentNotificationQueue implements JobRunner {
    private static final int LOCK_STRIPES = 64;
    private static final Logger log = LoggerFactory.getLogger(PersistentNotificationQueue.class);
    private static final JobRunnerKey RUNNER = JobRunnerKey.of("ru.noname.atlassian.jira.in.notification-queue");
    private static final JobId JOB = JobId.of("ru.noname.atlassian.jira.in.notification-queue");
    private final ActiveObjects ao;
    private final SchedulerService scheduler;
    private final ClusterLockService locks;
    private final ExecutorService workers;
    private volatile BiConsumer<String, DeliveryProgress> handler;
    private volatile boolean stopped;

    @Inject
    public PersistentNotificationQueue(@ComponentImport ActiveObjects ao,
                                       @ComponentImport SchedulerService scheduler,
                                       @ComponentImport ClusterLockService locks,
                                       @ComponentImport ThreadLocalDelegateExecutorFactory delegates) {
        this.ao = ao;
        this.scheduler = scheduler;
        this.locks = locks;
        AtomicInteger number = new AtomicInteger();
        this.workers = delegates.createExecutorService(new ThreadPoolExecutor(4, 4, 0L,
                TimeUnit.SECONDS, new SynchronousQueue<>(),
                task -> new Thread(task, "issue-notifier-durable-" + number.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    /** Синхронно фиксирует событие в БД до возврата в диспетчер Jira. */
    public void enqueue(String payload) {
        Objects.requireNonNull(payload, "payload");
        ao.executeInTransaction(() -> {
            ao.create(NotificationJobEntity.class, new DBParam("PAYLOAD", payload),
                    new DBParam("AVAILABLE_AT", new Date()), new DBParam("ATTEMPTS", 0));
            return null;
        });
    }

    /** Устанавливает обработчик до регистрации scheduler; вызывается слушателем при старте. */
    public void start(BiConsumer<String, DeliveryProgress> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        scheduler.registerJobRunner(RUNNER, this);
        try {
            scheduler.scheduleJob(JOB, JobConfig.forJobRunnerKey(RUNNER)
                    .withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
                    .withSchedule(Schedule.forInterval(1_000, new Date(System.currentTimeMillis() + 30_000))));
        } catch (SchedulerServiceException e) {
            scheduler.unregisterJobRunner(RUNNER);
            throw new IllegalStateException("Не удалось запланировать очередь уведомлений", e);
        }
    }

    @Override
    public JobRunnerResponse runJob(JobRunnerRequest request) {
        if (stopped || handler == null) return JobRunnerResponse.success();
        try {
            NotificationJobEntity[] ready = ao.find(NotificationJobEntity.class,
                    Query.select().where("AVAILABLE_AT <= ?", new Date()).order("ID ASC").limit(16));
            for (NotificationJobEntity row : ready) {
                int id = row.getID();
                try {
                    workers.execute(() -> process(id));
                } catch (RejectedExecutionException e) {
                    break; // Строка остаётся в БД до следующего опроса.
                }
            }
            return JobRunnerResponse.success();
        } catch (RuntimeException e) {
            log.warn("Не удалось прочитать очередь уведомлений ({})", e.getClass().getSimpleName());
            return JobRunnerResponse.failed("Не удалось прочитать очередь уведомлений");
        }
    }

    private void process(int id) {
        Lock lock = locks.getLockForName("ru.noname.atlassian.jira.in.notification-stripe." + Math.floorMod(id, LOCK_STRIPES));
        if (!lock.tryLock()) return;
        try {
            if (stopped || Thread.currentThread().isInterrupted()) return;
            NotificationJobEntity[] rows = ao.find(NotificationJobEntity.class,
                    Query.select().where("ID = ?", id));
            if (rows.length == 0) return;
            NotificationJobEntity row = rows[0];
            if (row.getAvailableAt().after(new Date())) return;
            try {
                DeliveryProgress progress = new DeliveryProgress(row.getDeliveredRaw(), snapshot -> {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    String previous = row.getDeliveredRaw();
                    try {
                        ao.executeInTransaction(() -> {
                            row.setDeliveredRaw(snapshot);
                            row.save();
                            return null;
                        });
                    } catch (RuntimeException failure) {
                        // AO proxy может сохранить dirty-поле после rollback: retry не должен подтвердить доставку.
                        row.setDeliveredRaw(previous);
                        throw failure;
                    }
                });
                handler.accept(row.getPayload(), progress);
                if (Thread.currentThread().isInterrupted()) return;
                ao.executeInTransaction(() -> {
                    ao.delete(row);
                    return null;
                });
            } catch (RuntimeException e) {
                if (stopped || Thread.currentThread().isInterrupted()) return;
                int attempts = row.getAttempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : row.getAttempts() + 1;
                long delaySeconds = Math.min(300, 1L << Math.min(9, attempts));
                ao.executeInTransaction(() -> {
                    row.setAttempts(attempts);
                    row.setLastError(NotificationErrors.summarize(e));
                    row.setLastFailedAt(new Date());
                    row.setAvailableAt(new Date(System.currentTimeMillis() + delaySeconds * 1_000));
                    row.save();
                    return null;
                });
                log.warn("Задание уведомлений {} отложено, попытка {} ({})", id, attempts, e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.warn("Задание уведомлений {} осталось в очереди ({})", id, e.getClass().getSimpleName());
        } finally {
            lock.unlock();
        }
    }

    /** Останавливает только локальные потоки; общее расписание и строки AO сохраняются. */
    public void stop() {
        stopped = true;
        scheduler.unregisterJobRunner(RUNNER);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
