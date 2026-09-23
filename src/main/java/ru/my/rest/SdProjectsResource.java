package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.type.ProjectTypeKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Список Service Desk-проектов для вкладки «SD-проекты» в настройках плагина.
 * Отдаёт все SD-проекты инстанса (без фильтра по правам на просмотр — страница
 * и так доступна только администратору Jira).
 */
@Named
@Path("/admin/sd-projects")
@Produces(MediaType.APPLICATION_JSON)
public class SdProjectsResource {

    /** Тип проекта, который создаёт плагин Jira Service Desk. */
    static final ProjectTypeKey SERVICE_DESK = new ProjectTypeKey("service_desk");

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;
    private final ProjectManager projectManager;

    @Inject
    public SdProjectsResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager,
            @ComponentImport ProjectManager projectManager) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
        this.projectManager = projectManager;
    }

    @GET
    public Response list() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();

        List<PickerItemDto> results = projectManager.getProjectObjects().stream()
                .filter(p -> SERVICE_DESK.equals(p.getProjectTypeKey()))
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new PickerItemDto(p.getKey(), p.getName() + " (" + p.getKey() + ")"))
                .collect(Collectors.toList());

        return Response.ok(results).build();
    }
}
