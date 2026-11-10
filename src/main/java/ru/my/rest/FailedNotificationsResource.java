package ru.my.rest;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.comments.CommentPermissionManager;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import net.java.ao.Query;
import ru.my.ao.NotificationJobEntity;
import ru.my.model.QueuedIssueEvent;
import ru.my.model.DiffResult;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Страницы ошибок очереди; право администратора не заменяет право видеть задачу. */
@Named
@Path("/admin/failed-notifications")
@Produces(MediaType.APPLICATION_JSON)
public class FailedNotificationsResource {
    private static final int PAGE_SIZE = 50;
    private final ActiveObjects ao;
    private final JiraAuthenticationContext auth;
    private final GlobalPermissionManager globals;
    private final IssueManager issues;
    private final PermissionManager permissions;
    private final ApplicationProperties properties;
    private final CommentManager comments;
    private final CommentPermissionManager commentPermissions;

    @Inject
    public FailedNotificationsResource(@ComponentImport ActiveObjects ao,
                                      @ComponentImport JiraAuthenticationContext auth,
                                      @ComponentImport GlobalPermissionManager globals,
                                      @ComponentImport IssueManager issues,
                                      @ComponentImport PermissionManager permissions,
                                      @ComponentImport ApplicationProperties properties,
                                      @ComponentImport CommentManager comments,
                                      @ComponentImport CommentPermissionManager commentPermissions) {
        this.ao = ao;
        this.auth = auth;
        this.globals = globals;
        this.issues = issues;
        this.permissions = permissions;
        this.properties = properties;
        this.comments = comments;
        this.commentPermissions = commentPermissions;
    }

    @GET
    public Response get(@QueryParam("beforeId") String beforeId) {
        ApplicationUser user = auth.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globals.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();
        Integer cursor = null;
        if (beforeId != null) {
            try {
                cursor = Integer.valueOf(beforeId);
                if (cursor <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return UserSettingsResource.badRequest("beforeId должен быть положительным целым числом");
            }
        }
        Query query = Query.select().where(cursor == null ? "ATTEMPTS > ?" : "ATTEMPTS > ? AND ID < ?",
                cursor == null ? new Object[]{0} : new Object[]{0, cursor}).order("ID DESC").limit(PAGE_SIZE + 1);
        NotificationJobEntity[] rows = ao.find(NotificationJobEntity.class, query);
        List<FailedNotificationDto> items = new ArrayList<>();
        int scanned = Math.min(rows.length, PAGE_SIZE);
        for (int i = 0; i < scanned; i++) {
            FailedNotificationDto item = describe(rows[i], user);
            if (item != null) items.add(item);
        }
        Integer next = rows.length > PAGE_SIZE ? rows[PAGE_SIZE - 1].getID() : null;
        return Response.ok(new FailedNotificationDto.Page(items, next)).build();
    }

    private FailedNotificationDto describe(NotificationJobEntity row, ApplicationUser user) {
        QueuedIssueEvent event;
        try {
            event = QueuedIssueEvent.decode(row.getPayload());
            if (event.issueId() == null) throw new IllegalArgumentException();
        } catch (RuntimeException malformed) {
            FailedNotificationDto item = basic(row);
            item.event = "Некорректное событие";
            item.context = "Метаданные события недоступны";
            item.error = "Не удалось прочитать сохранённое событие";
            return item;
        }
        Issue issue = issues.getIssueObject(event.issueId());
        if (issue == null || !permissions.hasPermission(ProjectPermissions.BROWSE_PROJECTS, issue, user)) return null;
        if (event.hasComment() && event.commentId() != null) {
            Comment comment = comments.getCommentById(event.commentId());
            if (comment == null || !commentPermissions.hasBrowsePermission(user, comment)) return null;
        }
        FailedNotificationDto item = basic(row);
        item.issueId = issue.getId();
        item.issueKey = issue.getKey();
        item.issueUrl = properties.getBaseUrl(UrlMode.CANONICAL) + "/browse/" + issue.getKey();
        item.event = event.hasComment() ? "Комментарий" : "Изменение задачи";
        List<String> details = new ArrayList<>();
        if (event.hasComment()) details.add("Комментарий №" + event.commentId());
        for (DiffResult.FieldChange change : event.diff().getChanges()) {
            if ("assignee".equalsIgnoreCase(change.fieldName())) details.add("Изменение исполнителя");
            else if ("status".equalsIgnoreCase(change.fieldName())) details.add("Изменение статуса");
            else details.add("Поле: " + change.fieldName());
        }
        item.context = String.join("; ", details);
        item.error = row.getLastError() == null ? "Подробности появятся после следующей попытки" : row.getLastError();
        return item;
    }

    private static FailedNotificationDto basic(NotificationJobEntity row) {
        FailedNotificationDto item = new FailedNotificationDto();
        item.id = row.getID();
        item.attempts = row.getAttempts();
        item.lastFailedAt = millis(row.getLastFailedAt());
        item.nextAttemptAt = millis(row.getAvailableAt());
        return item;
    }

    private static Long millis(Date value) {
        return value == null ? null : value.getTime();
    }
}
