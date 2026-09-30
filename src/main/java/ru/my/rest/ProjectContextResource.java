package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationAction;
import ru.my.model.ProjectContext;
import ru.my.model.ProjectContexts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Контексты проектов для админ-страницы: {@code GET} и {@code PUT}
 * {@code /rest/issue-notifier/1.0/admin/contexts}.
 * <p>
 * Список правится целиком одним PUT, без отдельных POST и DELETE на контекст:
 * все контексты лежат в одном ключе настроек, поэтому запись списка и так
 * атомарна, а клиенту не приходится собирать идентификаторы самому.
 * Встроенный контекст «Остальные проекты» удалить нельзя — если его нет в теле
 * запроса, он сохраняется с прежними настройками.
 */
@Named
@Path("/admin/contexts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectContextResource {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextResource.class);

    /** Длина имени: поле в один ключ настроек, а на вкладке оно должно читаться. */
    static final int MAX_NAME = 100;

    /** Разумный предел числа контекстов — все они лежат в одном значении настройки. */
    static final int MAX_CONTEXTS = 50;

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;
    private final AdminSettingsService adminSettingsService;

    @Inject
    public ProjectContextResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager,
            AdminSettingsService adminSettingsService) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
        this.adminSettingsService = adminSettingsService;
    }

    @GET
    public Response get() {
        Response denied = requireAdmin();
        if (denied != null) return denied;

        log.debug("Запрошены контексты проектов");
        List<ProjectContextDto> body = new ArrayList<>();
        for (ProjectContext context : contexts()) {
            body.add(ProjectContextDto.from(context));
        }
        return Response.ok(body).build();
    }

    @PUT
    public Response save(List<ProjectContextDto> body) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        log.debug("Сохранение контекстов проектов: {}", body == null ? "тело не задано" : body.size());
        if (body == null) return UserSettingsResource.badRequest("Тело запроса не задано");
        if (body.size() > MAX_CONTEXTS) {
            return UserSettingsResource.badRequest("Слишком много контекстов: не больше " + MAX_CONTEXTS);
        }

        List<ProjectContext> incoming = new ArrayList<>();
        ProjectContext defaultContext = null;
        Set<String> ids = new HashSet<>();
        Set<String> seenProjects = new HashSet<>();
        Set<String> seenCategories = new HashSet<>();

        for (ProjectContextDto dto : body) {
            if (dto == null) return UserSettingsResource.badRequest("Пустой контекст в списке");

            ProjectContext context = dto.toModel();
            if (context.isDefault()) {
                if (defaultContext != null) {
                    return UserSettingsResource.badRequest("Контекст «Остальные проекты» указан дважды");
                }
                defaultContext = context;
            }

            Response invalid = validate(context);
            if (invalid != null) return invalid;

            // id назначает сервер: присланный клиентом id пустой у нового контекста
            // и должен сохраниться у существующего, иначе настройки потеряются
            String id = context.isDefault() ? ProjectContexts.DEFAULT_ID : resolveId(context.id());
            if (!ids.add(id)) {
                return UserSettingsResource.badRequest("Контекст повторяется: " + id);
            }

            // проект и категория живут ровно в одном контексте: иначе выбор между
            // ними был бы неявным, а настройки — неотличимыми на глаз
            for (String project : context.projects()) {
                if (!seenProjects.add(project)) {
                    return UserSettingsResource.badRequest(
                            "Проект " + project + " указан в двух контекстах");
                }
            }
            for (String category : context.categories()) {
                if (!seenCategories.add(category)) {
                    return UserSettingsResource.badRequest(
                            "Категория проектов указана в двух контекстах");
                }
            }

            if (!context.isDefault()) {
                incoming.add(withId(context, id));
            }
        }

        // «Остальные проекты» не пришли — сохраняем прежние настройки этого контекста
        if (defaultContext == null) {
            defaultContext = ProjectContexts.byId(contexts(), ProjectContexts.DEFAULT_ID);
        }
        incoming.add(defaultContext != null ? defaultContext : ProjectContexts.emptyDefault());

        adminSettingsService.set(ProjectContexts.KEY, ProjectContexts.format(incoming));
        return Response.noContent().build();
    }

    private Response validate(ProjectContext context) {
        String name = context.name().trim();
        if (!context.isDefault() && name.isEmpty()) {
            return UserSettingsResource.badRequest("У контекста не задано название");
        }
        if (name.length() > MAX_NAME) {
            return UserSettingsResource.badRequest("Название длиннее " + MAX_NAME + " символов");
        }
        for (String key : context.actionKeys()) {
            if (NotificationAction.byKey(key) == null) {
                return UserSettingsResource.badRequest("Неизвестное действие: " + key);
            }
        }
        // ponytail: значения получателей не проверяем — справочник живёт в ru.my.impl,
        // а слой rest от impl намеренно не зависит. Неизвестный получатель при отправке
        // просто игнорируется, как и до появления контекстов. Понадобится проверка —
        // переносить справочник в ru.my.model, а не импортировать impl сюда
        return null;
    }

    /**
     * Идентификатор существующего контекста сохраняется, новому выдаётся
     * случайный: счётчик дал бы гонку при двух администраторах в Data Center.
     */
    private String resolveId(String incoming) {
        return ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString().substring(0, 8);
    }

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[0-9a-f]{8}");

    private static ProjectContext withId(ProjectContext context, String id) {
        if (id.equals(context.id())) {
            return context;
        }
        Map<String, ProjectContext.ActionSetting> actions = new java.util.LinkedHashMap<>();
        for (NotificationAction action : NotificationAction.values()) {
            ProjectContext.ActionSetting setting = context.action(action);
            if (setting != null) {
                actions.put(action.key(), setting);
            }
        }
        return new ProjectContext(id, context.name(), context.projects(), context.categories(),
                actions, context.closedStatuses());
    }

    private List<ProjectContext> contexts() {
        return ProjectContexts.parse(adminSettingsService.get(ProjectContexts.KEY, ""));
    }

    private Response requireAdmin() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) {
            return UserSettingsResource.forbidden();
        }
        return null;
    }
}
