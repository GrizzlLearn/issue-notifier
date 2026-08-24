package ru.my.impl;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationService;
import ru.my.model.DiffResult;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Слушатель событий Jira. Парсит changelog в потоке Jira-события (C1),
 * затем перекладывает рассылку в пул потоков, не блокируя поток события.
 * Использует {@link ThreadLocalDelegateExecutorFactory} для передачи ThreadLocal-контекста.
 * <p>
 * Пул: core=2, max=4 потока (C3) — параллельная обработка нескольких событий.
 * Очередь ограничена {@value #QUEUE_CAPACITY} задачами; переполнение логируется.
 */
@Named
public class IssueEventListener {

    private static final Logger log = LoggerFactory.getLogger(IssueEventListener.class);
    private static final int QUEUE_CAPACITY = 1000;
    private static final int SHUTDOWN_TIMEOUT_SEC = 10;
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;

    private final EventPublisher eventPublisher;
    private final ExecutorService executor;
    private final NotificationService notificationService;

    @Inject
    public IssueEventListener(
            @ComponentImport EventPublisher eventPublisher,
            @ComponentImport ThreadLocalDelegateExecutorFactory delegateExecutorFactory,
            NotificationService notificationService) {
        this.eventPublisher = eventPublisher;
        this.notificationService = notificationService;

        AtomicInteger threadCounter = new AtomicInteger();
        RejectedExecutionHandler discardWithLog = (r, pool) ->
                log.warn("Очередь уведомлений переполнена ({}), событие отброшено", QUEUE_CAPACITY);

        ExecutorService bounded = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAX_POOL_SIZE, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> new Thread(r, "issue-notifier-worker-" + threadCounter.incrementAndGet()),
                discardWithLog);

        this.executor = delegateExecutorFactory.createExecutorService(bounded);
    }

    /** Конструктор для unit-тестов — не регистрируется в eventPublisher. */
    public IssueEventListener(ExecutorService executor, NotificationService notificationService) {
        this.eventPublisher = null;
        this.executor = executor;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void init() {
        if (eventPublisher != null) {
            eventPublisher.register(this);
        }
    }

    @EventListener
    public void onIssueEvent(IssueEvent event) {
        if (isIgnoredEvent(event)) {
            return;
        }
        // C1: changelog читается здесь, в потоке Jira-события — OFBiz-ленивая загрузка
        // через getRelated("ChildChangeItem") безопасна только в этом контексте.
        DiffResult diff = DiffFormatter.parse(event.getChangeLog());
        if (diff.isEmpty()) {
            return;
        }
        Issue issue = event.getIssue();
        ApplicationUser author = event.getUser();
        // typeId извлекается до submit — event не должен утекать в рабочий поток (C1)
        Long typeId = event.getEventTypeId();
        try {
            executor.submit(() -> {
                try {
                    notificationService.processEvent(issue, author, diff);
                } catch (Exception e) {
                    log.error("Необработанная ошибка при обработке события {}: {}",
                            typeId, e.getMessage(), e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Бросается только если executor завершён (shutdown); переполнение очереди
            // обрабатывается silently DiscardPolicy-хендлером внутри ThreadPoolExecutor
            log.warn("Executor завершён, событие {} отброшено", typeId);
        }
    }

    @PreDestroy
    public void destroy() {
        if (eventPublisher != null) {
            eventPublisher.unregister(this);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                log.warn("Executor не завершился за {} сек, принудительная остановка", SHUTDOWN_TIMEOUT_SEC);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Создание и удаление задачи не генерируют diff с изменёнными полями,
     * поэтому их пропускаем сразу, не тратя ресурсы на парсинг changelog.
     */
    private boolean isIgnoredEvent(IssueEvent event) {
        Long typeId = event.getEventTypeId();
        return EventType.ISSUE_CREATED_ID.equals(typeId)
                || EventType.ISSUE_DELETED_ID.equals(typeId);
    }
}
