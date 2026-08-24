package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Named
@Path("/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSettingsResource {

    // Все известные ключи глобальных настроек плагина
    static final List<String> KNOWN_KEYS = List.of(
            "email.enabled",
            "mattermost.enabled",
            "mattermost.domain",
            "mattermost.token",
            "mattermost.botId",
            "telegram.enabled",
            "telegram.botToken"
    );

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;
    private final AdminSettingsService adminSettingsService;

    @Inject
    public AdminSettingsResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager,
            AdminSettingsService adminSettingsService) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
        this.adminSettingsService = adminSettingsService;
    }

    @GET
    public Response get() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!isAdmin(user)) return UserSettingsResource.forbidden();

        Map<String, String> settings = KNOWN_KEYS.stream()
                .collect(Collectors.toMap(k -> k, k -> adminSettingsService.get(k, "")));

        return Response.ok(settings).build();
    }

    @PUT
    public Response set(Map<String, String> body) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!isAdmin(user)) return UserSettingsResource.forbidden();
        if (body == null || body.isEmpty()) return UserSettingsResource.badRequest("Тело запроса не задано");

        body.entrySet().stream()
                .filter(e -> KNOWN_KEYS.contains(e.getKey()))
                .forEach(e -> adminSettingsService.set(e.getKey(), e.getValue()));

        return Response.noContent().build();
    }

    private boolean isAdmin(ApplicationUser user) {
        return globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user);
    }
}
