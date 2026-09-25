package ru.my.impl;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import com.atlassian.sal.api.executor.ThreadLocalDelegateExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationService;
import ru.my.impl.util.MentionParser;
import ru.my.model.DiffResult;
import ru.my.model.NotificationAction;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ru.my.model.ClosingStatuses;

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

    /** ponytail: обрезаем текст комментария; лимит сообщения Telegram — 4096 символов. */
    private static final int COMMENT_LIMIT = 500;

    private final EventPublisher eventPublisher;
    private final ExecutorService executor;
    private final NotificationService notificationService;
    private final UserManager userManager;
    private final ApplicationProperties applicationProperties;
    private final AdminSettingsService adminSettingsService;
    private final JsonEntityPropertyManager entityProperties;

    @Inject
    public IssueEventListener(
            @ComponentImport EventPublisher eventPublisher,
            @ComponentImport ThreadLocalDelegateExecutorFactory delegateExecutorFactory,
            @ComponentImport UserManager userManager,
            @ComponentImport ApplicationProperties applicationProperties,
            @ComponentImport JsonEntityPropertyManager entityProperties,
            NotificationService notificationService,
            AdminSettingsService adminSettingsService) {
        this.eventPublisher = eventPublisher;
        this.entityProperties = entityProperties;
        this.notificationService = notificationService;
        this.userManager = userManager;
        this.applicationProperties = applicationProperties;
        this.adminSettingsService = adminSettingsService;

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
    public IssueEventListener(ExecutorService executor, NotificationService notificationService,
                              UserManager userManager, ApplicationProperties applicationProperties,
                              AdminSettingsService adminSettingsService,
                              JsonEntityPropertyManager entityProperties) {
        this.eventPublisher = null;
        this.entityProperties = entityProperties;
        this.executor = executor;
        this.notificationService = notificationService;
        this.userManager = userManager;
        this.applicationProperties = applicationProperties;
        this.adminSettingsService = adminSettingsService;
    }

    @PostConstruct
    public void init() {
        if (eventPublisher != null) {
            eventPublisher.register(this);
        }
    }

    /**
     * Ошибка здесь уходит в диспетчер событий Jira и мешает другим слушателям,
     * поэтому разбор события обёрнут целиком: внутри рабочих задач ошибки уже
     * ловит {@link #submit}.
     */
    @EventListener
    public void onIssueEvent(IssueEvent event) {
        try {
            handleIssueEvent(event);
        } catch (Exception e) {
            log.error("Ошибка разбора события {}: {}", event.getEventTypeId(), e.getMessage(), e);
        }
    }

    private void handleIssueEvent(IssueEvent event) {
        Issue issue = event.getIssue();
        ApplicationUser author = event.getUser();
        // typeId извлекается до submit — event не должен утекать в рабочий поток (C1)
        Long typeId = event.getEventTypeId();

        Comment comment = event.getComment();
        if (comment != null && EventType.ISSUE_COMMENTED_ID.equals(typeId)) {
            // тело комментария и его ограничения читаются здесь же, в потоке события;
            // правка комментария уведомлений не порождает — иначе исправленная
            // опечатка уходила бы как новый комментарий
            String body = comment.getBody();
            Long commentId = comment.getId();
            boolean restrictedByLevel = CommentVisibility.isRestrictedByLevel(comment);
            submit(typeId, () -> processComment(issue, author, body, commentId, restrictedByLevel));
        }

        if (isIgnoredEvent(event)) {
            return;
        }
        // C1: changelog читается здесь, в потоке Jira-события — OFBiz-ленивая загрузка
        // через getRelated("ChildChangeItem") безопасна только в этом контексте.
        DiffResult diff = DiffFormatter.parse(event.getChangeLog());
        if (diff.isEmpty()) {
            return;
        }
        // одна задача на событие: сначала уведомления о действиях, затем рассылка
        // об изменении полей — без тех, кому уже ушло. Настройки и исполнитель
        // читаются здесь же, в рабочем потоке: в потоке Jira-события обращений
        // к БД быть не должно.
        submit(typeId, () -> {
            List<ApplicationUser> notified = new ArrayList<>();
            DiffResult.FieldChange assigneeChange = changeOf(diff, "assignee");
            if (assigneeChange != null) {
                notified.addAll(notifyAssigned(issue, author, assigneeChange));
            }
            DiffResult.FieldChange statusChange = changeOf(diff, "status");
            if (statusChange != null && isClosingTransition(issue, statusChange.toId())) {
                notified.addAll(notificationService.processAction(issue, author, NotificationAction.CLOSED,
                        List.of(), placeholders(issue, author, "status", nullToEmpty(statusChange.toValue()))));
            }
            notificationService.processEvent(issue, author, diff, notified);
        });
    }

    /**
     * Упомянутые получают {@link NotificationAction#MENTION}, остальные —
     * {@link NotificationAction#COMMENT_ADDED}. Упоминание приоритетнее: тому,
     * кому уже ушло сообщение об упоминании, второе как исполнителю не отправляется.
     * <p>
     * Ограничения видимости: внутренний комментарий Service Desk уходит только
     * исполнителю задачи, комментарий с ограничением по группе или роли —
     * всем получателям, но без текста (для этого текст не попадает в значения
     * плейсхолдеров, и сервис берёт шаблон «без текста комментария»).
     */
    private void processComment(Issue issue, ApplicationUser author, String body,
                                Long commentId, boolean restrictedByLevel) {
        // свойство комментария — запрос в БД, поэтому читается в рабочем потоке
        if (CommentVisibility.isServiceDeskInternal(commentId, entityProperties)) {
            notifyAssigneeOnly(issue, author, body);
            return;
        }

        Map<String, String> values = restrictedByLevel
                ? placeholders(issue, author)
                : placeholders(issue, author, "comment", truncate(body));

        List<ApplicationUser> mentioned = resolveUsers(MentionParser.parse(body));
        List<ApplicationUser> notified = mentioned.isEmpty()
                ? List.of()
                : notificationService.processAction(issue, author, NotificationAction.MENTION, mentioned, values);

        notificationService.processAction(issue, author, NotificationAction.COMMENT_ADDED,
                List.of(), values, notified);
    }

    /** Внутренний комментарий Service Desk виден только команде — уведомляем исполнителя. */
    private void notifyAssigneeOnly(Issue issue, ApplicationUser author, String body) {
        ApplicationUser assignee = issue.getAssignee();
        if (assignee == null) {
            return;
        }
        notificationService.processAction(issue, author, NotificationAction.COMMENT_ADDED,
                List.of(assignee), placeholders(issue, author, "comment", truncate(body)));
    }

    /** Резолвит имена из упоминаний в пользователей; неизвестные имена отбрасываются. */
    private List<ApplicationUser> resolveUsers(List<String> names) {
        List<ApplicationUser> users = new ArrayList<>();
        for (String name : names) {
            ApplicationUser user = userManager.getUserByName(name);
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }

    /** @return изменение поля из changelog или {@code null}, если поле не менялось */
    private static DiffResult.FieldChange changeOf(DiffResult diff, String fieldName) {
        return diff.getChanges().stream()
                .filter(c -> fieldName.equalsIgnoreCase(c.fieldName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Назначение исполнителем: уведомление уходит тому, кого назначили.
     * Снятие исполнителя получателя не даёт, а назначивший себя сам отсеивается
     * дальше по конвейеру как автор события.
     * <p>
     * Исполнитель берётся из changelog, а не из {@code issue.getAssignee()}:
     * задачу могли переназначить ещё раз, пока событие ждало в очереди, и тогда
     * уведомление ушло бы не тому.
     *
     * @return кому сообщение реально ушло — им не нужна вторая рассылка об изменении полей
     */
    private List<ApplicationUser> notifyAssigned(Issue issue, ApplicationUser author,
                                                 DiffResult.FieldChange change) {
        if (change.toId() == null || change.toId().isBlank()) {
            return List.of(); // исполнителя сняли
        }
        ApplicationUser assignee = userManager.getUserByKey(change.toId());
        if (assignee == null) {
            return List.of();
        }
        return notificationService.processAction(issue, author, NotificationAction.ASSIGNED,
                List.of(assignee), placeholders(issue, author, "assignee", assignee.getDisplayName()));
    }

    /**
     * Закрывающим считается только статус, выбранный администратором для проекта
     * задачи на вкладке «Действия». Проект без выбранных статусов уведомлений
     * о закрытии не шлёт — правила по категории статуса нет.
     * <p>
     * Статус берётся из changelog, а не из задачи: к моменту рассылки её могли
     * перевести дальше, и закрытие бы потерялось.
     */
    private boolean isClosingTransition(Issue issue, String newStatusId) {
        if (newStatusId == null || newStatusId.isBlank()) {
            return false;
        }
        String projectKey = issue.getProjectObject() != null ? issue.getProjectObject().getKey() : "";
        return ClosingStatuses.isClosing(
                adminSettingsService.get(ClosingStatuses.KEY, ""), projectKey, newStatusId);
    }

    private Map<String, String> placeholders(Issue issue, ApplicationUser author,
                                             String extraKey, String extraValue) {
        Map<String, String> values = placeholders(issue, author);
        values.put(extraKey, extraValue);
        return values;
    }

    private Map<String, String> placeholders(Issue issue, ApplicationUser author) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("issueKey", issue.getKey());
        values.put("issueUrl", applicationProperties.getBaseUrl(UrlMode.CANONICAL) + "/browse/" + issue.getKey());
        values.put("summary", issue.getSummary());
        values.put("project", issue.getProjectObject() != null ? issue.getProjectObject().getName() : "");
        values.put("author", author != null ? author.getDisplayName() : "");
        return values;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= COMMENT_LIMIT ? text : text.substring(0, COMMENT_LIMIT) + "…";
    }

    private void submit(Long typeId, Runnable task) {
        try {
            executor.submit(() -> {
                try {
                    task.run();
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
