package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.api.UserSettingsService;
import ru.my.impl.ChannelKeys;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

@Named
@Path("/user/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserSettingsResource {

    private final JiraAuthenticationContext authContext;
    private final UserSettingsService userSettingsService;
    private final AdminSettingsService adminSettingsService;

    @Inject
    public UserSettingsResource(
            @ComponentImport JiraAuthenticationContext authContext,
            UserSettingsService userSettingsService,
            AdminSettingsService adminSettingsService) {
        this.authContext = authContext;
        this.userSettingsService = userSettingsService;
        this.adminSettingsService = adminSettingsService;
    }

    @GET
    public Response get() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return unauthorized();
        String botUsername = adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_USERNAME, "");
        return Response.ok(UserSettingsDto.from(userSettingsService.getSettings(user), botUsername)).build();
    }

    @PUT
    public Response save(UserSettingsDto dto) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return unauthorized();
        if (dto == null) return badRequest("Тело запроса не задано");
        userSettingsService.saveSettings(user, dto.toModel());
        return Response.noContent().build();
    }

    static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Требуется аутентификация")).build();
    }

    static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message)).build();
    }

    static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", message)).build();
    }

    static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "Недостаточно прав")).build();
    }
}
