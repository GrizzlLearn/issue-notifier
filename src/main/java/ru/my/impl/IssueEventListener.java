package ru.my.impl;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PreDestroy;
import ru.my.api.NotificationService;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Слушатель событий Jira. Перекладывает обработку в отдельный поток,
 * не блокируя поток Jira-события. Использует {@link ThreadLocalDelegateExecutorService},
 * чтобы ThreadLocal-контекст (активный пользователь, транзакция) передавался
 * в задачу корректно.
 * <p>
 * Регистрируется в {@link EventPublisher} при создании бина и снимается
 * при уничтожении плагина через {@link DisposableBean}.
 */
@Named
public class IssueEventListener {

    private static final Logger log = LoggerFactory.getLogger(IssueEventListener.class);

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
        this.executor = delegateExecutorFactory.createExecutorService(
                Executors.newSingleThreadExecutor(
                        r -> new Thread(r, "issue-notifier-worker")));
        eventPublisher.register(this);
    }

    /**
     * Конструктор для unit-тестов — принимает готовый executor без регистрации
     * в eventPublisher.
     */
    public IssueEventListener(ExecutorService executor, NotificationService notificationService) {
        this.eventPublisher = null;
        this.executor = executor;
        this.notificationService = notificationService;
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
