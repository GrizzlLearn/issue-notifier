package ru.my.rest;

import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Поиск/резолв проектов для пикера в пользовательских настройках.
 * <p>
 * Бэкенд отдаёт готовые {@code {value,label}} — фронтенд не ходит в REST API самой Jira
 * напрямую. {@link ProjectManager#getProjectObjects()} уже кэшируется Jira внутри,
 * поэтому отдельный кэш с периодическим обновлением на стороне плагина не нужен.
 */
@Named
@Path("/projects")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectPickerResource {

    private static final int MAX_RESULTS = 20;

    private final JiraAuthenticationContext authContext;
    private final ProjectManager projectManager;
    private final PermissionManager permissionManager;

    @Inject
    public ProjectPickerResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport ProjectManager projectManager,
            @ComponentImport PermissionManager permissionManager) {
        this.authContext = authContext;
        this.projectManager = projectManager;
        this.permissionManager = permissionManager;
    }

    @GET
    public Response search(@QueryParam("query") String query) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<PickerItemDto> results = projectManager.getProjectObjects().stream()
                .filter(p -> permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, p, user))
                .filter(p -> q.isEmpty()
                        || p.getKey().toLowerCase(Locale.ROOT).contains(q)
                        || p.getName().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_RESULTS)
                .map(p -> new PickerItemDto(p.getKey(), p.getName() + " (" + p.getKey() + ")"))
                .collect(Collectors.toList());

        return Response.ok(results).build();
    }

    @GET
    @Path("/{key}")
    public Response resolve(@PathParam("key") String key) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();

        Project project = projectManager.getProjectObjByKey(key);
        if (project == null || !permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, project, user)) {
            return UserSettingsResource.notFound("Проект не найден: " + key);
        }
        return Response.ok(new PickerItemDto(project.getKey(), project.getName() + " (" + project.getKey() + ")")).build();
    }
}
