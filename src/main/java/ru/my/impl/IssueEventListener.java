package ru.my.impl;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import ru.my.model.QueuedIssueEvent;
import ru.my.model.DeliveryProgress;
import ru.my.model.NotificationDeliveryException;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationService;
import ru.my.impl.util.MentionParser;
import ru.my.impl.util.TextLimit;
import ru.my.model.CommentScope;
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

/** Сохраняет снимки событий в БД; очередь восстанавливает незавершённую рассылку. */
@Named
public class IssueEventListener {
    private static final Logger log = LoggerFactory.getLogger(IssueEventListener.class);
    private static final int COMMENT_LIMIT = 500;
    private final EventPublisher eventPublisher;
    private final PersistentNotificationQueue queue;
    private final IssueManager issueManager;
    private final NotificationService notificationService;
    private final UserManager userManager;
    private final ApplicationProperties applicationProperties;
    private final AdminSettingsService adminSettingsService;
    private final JsonEntityPropertyManager entityProperties;

    @Inject
    public IssueEventListener(@ComponentImport EventPublisher eventPublisher,
                              PersistentNotificationQueue queue,
                              @ComponentImport IssueManager issueManager,
                              @ComponentImport UserManager userManager,
                              @ComponentImport ApplicationProperties applicationProperties,
                              @ComponentImport JsonEntityPropertyManager entityProperties,
                              NotificationService notificationService,
                              AdminSettingsService adminSettingsService) {
        this.eventPublisher = eventPublisher;
        this.queue = queue;
        this.issueManager = issueManager;
        this.userManager = userManager;
        this.applicationProperties = applicationProperties;
        this.entityProperties = entityProperties;
        this.notificationService = notificationService;
        this.adminSettingsService = adminSettingsService;
    }

    @PostConstruct
    public void init() {
        queue.start(this::processQueuedEvent);
        eventPublisher.register(this);
    }

    /**
     * Ошибка здесь уходит в диспетчер событий Jira и мешает другим слушателям,
     * поэтому разбор события обёрнут целиком. Ошибки обработки сохранённого
     * события передаются очереди для повторной попытки.
     */
    @EventListener
    public void onIssueEvent(IssueEvent event) {
        try {
            handleIssueEvent(event);
        } catch (Exception e) {
            log.error("Задача {}: ошибка сохранения или разбора события {}",
                    event.getIssue() == null ? "?" : event.getIssue().getKey(), event.getEventTypeId(), e);
        }
    }

    private void handleIssueEvent(IssueEvent event) {
        // bulk-операция или workflow-функция с отключёнными уведомлениями: Jira
        // просит не рассылать, и это относится и к нашим каналам, иначе галка
        // «без уведомлений» в массовой правке ничего не значит
        if (!event.isSendMail()) {
            log.debug("Событие {} по задаче {}: Jira просила не рассылать уведомления",
                    event.getEventTypeId(),
                    event.getIssue() == null ? "?" : event.getIssue().getKey());
            return;
        }
        Issue issue = event.getIssue();
        ApplicationUser author = event.getUser();
        // Сохраняются только значения: объект события не должен утекать в рабочий поток.
        Long typeId = event.getEventTypeId();

        Comment comment = event.getComment();
        boolean hasComment = comment != null && EventType.ISSUE_COMMENTED_ID.equals(typeId);
        DiffResult diff = isIgnoredEvent(event) ? new DiffResult(List.of()) : DiffFormatter.parse(event.getChangeLog());
        if (!hasComment && diff.isEmpty()) return;
        queue.enqueue(new QueuedIssueEvent(issue.getId(), author == null ? null : author.getKey(),
                typeId, hasComment, hasComment ? comment.getId() : null,
                hasComment ? comment.getBody() : null,
                hasComment && CommentVisibility.isRestrictedByLevel(comment), diff).encode());
    }

    void processQueuedEvent(String payload, DeliveryProgress progress) {
        QueuedIssueEvent event = QueuedIssueEvent.decode(payload);
        Issue issue = issueManager.getIssueObject(event.issueId());
        if (issue == null) {
            log.info("Задача {} удалена: событие {} пропущено", event.issueId(), event.typeId());
            return;
        }
        ApplicationUser author = event.authorKey() == null ? null : userManager.getUserByKey(event.authorKey());
        List<Exception> failures = new ArrayList<>();
        if (event.hasComment()) {
            step(issue, "комментарий", () -> processComment(issue, author, event.commentBody(),
                    event.commentId(), event.restrictedByLevel(), progress), failures);
        }
        DiffResult diff = event.diff();
        if (!diff.isEmpty()) {
            // шаги изолированы: сбой в уведомлении о назначении не должен стоить
            // уведомления о закрытии и рассылки наблюдателям
            List<ApplicationUser> notified = new ArrayList<>();
            DiffResult.FieldChange assigneeChange = changeOf(diff, "assignee");
            if (assigneeChange != null) {
                step(issue, "назначение исполнителя",
                        () -> notified.addAll(notifyAssigned(issue, author, assigneeChange, progress)), failures, notified);
            }
            DiffResult.FieldChange statusChange = changeOf(diff, "status");
            if (statusChange != null) {
                step(issue, "переход в закрывающий статус", () -> {
                    if (isClosingTransition(issue, statusChange.toId())) {
                        notified.addAll(notificationService.processAction(issue, author, NotificationAction.CLOSED,
                                List.of(), placeholders(issue, author, "status", nullToEmpty(statusChange.toValue())), notified, CommentScope.NONE, progress));
                    }
                }, failures, notified);
            }
            step(issue, "рассылка наблюдателям",
                    () -> notificationService.processEvent(issue, author, diff, notified, progress), failures);
        }
        rethrow(failures);
    }

    /**
     * Упомянутые получают {@link NotificationAction#MENTION}, остальные —
     * {@link NotificationAction#COMMENT_ADDED}. Упоминание приоритетнее: тому,
     * кому уже ушло сообщение об упоминании, второе как исполнителю не отправляется.
     * <p>
     * Ограничения видимости: внутренний комментарий Service Desk уходит только
     * исполнителю задачи, комментарий с ограничением по группе или роли —
     * получателям с правом видеть комментарий, но без текста (текст не попадает в значения
     * плейсхолдеров, и сервис берёт шаблон «без текста комментария»).
     */
    private void processComment(Issue issue, ApplicationUser author, String body,
                                Long commentId, boolean restrictedByLevel, DeliveryProgress progress) {
        // свойство комментария — запрос в БД, поэтому читается в рабочем потоке
        boolean internal = CommentVisibility.isServiceDeskInternal(commentId, entityProperties);
        CommentScope scope = new CommentScope(commentId, internal);

        // текст комментария с ограничением по группе или роли не идёт в значения
        // ни в одной ветке: получателя проверяет сервис, но шаблон «без текста»
        // выбирается здесь — и раньше внутренний комментарий Service Desk это
        // ограничение обходил, уходя с текстом целиком
        Map<String, String> values = restrictedByLevel
                ? placeholders(issue, author)
                : placeholders(issue, author, "comment", truncate(body));

        if (internal) {
            notifyAssigneeOnly(issue, author, values, scope, progress);
            return;
        }

        List<ApplicationUser> mentioned = resolveUsers(MentionParser.parse(body));
        List<ApplicationUser> notified = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        if (!mentioned.isEmpty()) {
            step(issue, "упоминания", () -> notified.addAll(notificationService.processAction(
                    issue, author, NotificationAction.MENTION, mentioned, values, List.of(), scope, progress)), failures, notified);
        }
        step(issue, "новый комментарий", () -> notificationService.processAction(issue, author,
                NotificationAction.COMMENT_ADDED, List.of(), values, notified, scope, progress), failures);
        rethrow(failures);
    }

    /**
     * Внутренний комментарий Service Desk виден только команде — уведомляем
     * исполнителя. Делегирование к такому комментарию не применяется: этим
     * занимается {@link CommentScope#blocksDelegation()}.
     */
    private void notifyAssigneeOnly(Issue issue, ApplicationUser author,
                                    Map<String, String> values, CommentScope scope, DeliveryProgress progress) {
        ApplicationUser assignee = issue.getAssignee();
        if (assignee == null) {
            return;
        }
        notificationService.processAction(issue, author, NotificationAction.COMMENT_ADDED,
                List.of(assignee), values, List.of(), scope, progress);
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
                                                 DiffResult.FieldChange change, DeliveryProgress progress) {
        if (change.toId() == null || change.toId().isBlank()) {
            return List.of(); // исполнителя сняли
        }
        ApplicationUser assignee = userManager.getUserByKey(change.toId());
        if (assignee == null) {
            return List.of();
        }
        return notificationService.processAction(issue, author, NotificationAction.ASSIGNED,
                List.of(assignee), placeholders(issue, author, "assignee", assignee.getDisplayName()),
                List.of(), CommentScope.NONE, progress);
    }

    /**
     * Закрывающим считается только статус, выбранный администратором для проекта
     * задачи в её контексте проектов. Проект без выбранных статусов уведомлений
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
        return NotificationServiceImpl.contextOf(issue, adminSettingsService)
                .isClosing(projectKey, newStatusId);
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
        return text.length() <= COMMENT_LIMIT ? text : TextLimit.cut(text, COMMENT_LIMIT) + "…";
    }

    /** Выполняет остальные шаги после ошибки; очередь повторит событие после их завершения. */
    private void step(Issue issue, String what, Runnable action, List<Exception> failures) {
        step(issue, what, action, failures, null);
    }

    private void step(Issue issue, String what, Runnable action, List<Exception> failures,
                      List<ApplicationUser> notified) {
        try {
            action.run();
        } catch (Exception e) {
            if (notified != null && e instanceof NotificationDeliveryException partial) {
                notified.addAll(partial.getNotifiedRecipients());
            }
            log.warn("Задача {}: шаг «{}» не выполнен", issue.getKey(), what, e);
            failures.add(e);
        }
    }

    private static void rethrow(List<Exception> failures) {
        if (failures.isEmpty()) return;
        IllegalStateException failure = new IllegalStateException("Не все уведомления события отправлены");
        failures.forEach(failure::addSuppressed);
        throw failure;
    }

    @PreDestroy
    public void destroy() {
        eventPublisher.unregister(this);
        queue.stop();
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
