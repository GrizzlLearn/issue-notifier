package ru.my.impl;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.NotificationService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Слушатель событий Jira. Перекладывает обработку в отдельный поток,
 * не блокируя поток Jira-события. Использует {@link ThreadLocalDelegateExecutorFactory},
 * чтобы ThreadLocal-контекст (активный пользователь, транзакция) передавался
 * в задачу корректно.
 * <p>
 * Очередь ограничена {@value #QUEUE_CAPACITY} задачами — при переполнении новые
 * события отбрасываются с предупреждением в лог, что позволяет избежать OOM
 * при массовом bulk-edit. Регистрируется в {@link EventPublisher} через
 * {@link PostConstruct}, когда все Spring-зависимости уже инициализированы.
 */
@Named
public class IssueEventListener {

    private static final Logger log = LoggerFactory.getLogger(IssueEventListener.class);
    private static final int QUEUE_CAPACITY = 1000;
    private static final int SHUTDOWN_TIMEOUT_SEC = 10;

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

        RejectedExecutionHandler discardWithLog = (r, pool) ->
                log.warn("Очередь уведомлений переполнена ({}), событие отброшено", QUEUE_CAPACITY);

        ExecutorService bounded = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> new Thread(r, "issue-notifier-worker"),
                discardWithLog);

        this.executor = delegateExecutorFactory.createExecutorService(bounded);
    }

    /**
     * Конструктор для unit-тестов — не регистрируется в eventPublisher.
     */
    public IssueEventListener(ExecutorService executor, NotificationService notificationService) {
        this.eventPublisher = null;
        this.executor = executor;
        this.notificationService = notificationService;
    }

    /** Регистрируется после полной инициализации всех Spring-зависимостей. */
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
        executor.submit(() -> {
            try {
                notificationService.processEvent(event);
            } catch (Exception e) {
                log.error("Необработанная ошибка при обработке события {}: {}",
                        event.getEventTypeId(), e.getMessage(), e);
            }
        });
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
