package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.api.UserSettingsService;
import ru.my.model.ActionTemplates;
import ru.my.model.ChannelKeys;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Named
@Path("/user/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserSettingsResource {

    /**
     * Telegram chat_id — целое число, у групп со знаком минус. Поле правит
     * пользователь руками, и мусор в нём иначе виден только в логах отправки.
     */
    private static final Pattern CHAT_ID = Pattern.compile("-?\\d{1,20}");

    /** Разумный предел для списка проектов: поле хранится одной строкой в AO. */
    static final int MAX_PROJECTS = 500;

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
        List<String> enabledChannels = Arrays.stream(NotificationChannel.values())
                .filter(adminSettingsService::isChannelEnabled)
                .map(NotificationChannel::name)
                .collect(Collectors.toList());
        boolean commentTextAllowed = !Boolean.parseBoolean(
                adminSettingsService.get(ActionTemplates.HIDE_COMMENT_TEXT_KEY, "false"));
        return Response.ok(UserSettingsDto.from(
                userSettingsService.getSettings(user), botUsername, enabledChannels, commentTextAllowed)).build();
    }

    @PUT
    public Response save(UserSettingsDto dto) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return unauthorized();
        if (dto == null) return badRequest("Тело запроса не задано");

        String chatId = dto.getTelegramChatId();
        if (chatId != null && !chatId.isBlank() && !CHAT_ID.matcher(chatId.trim()).matches()) {
            return badRequest("Telegram chat_id — это число; его присылает бот в ответ на /start");
        }
        if (dto.getProjects() != null && dto.getProjects().size() > MAX_PROJECTS) {
            return badRequest("Слишком много проектов: не больше " + MAX_PROJECTS);
        }

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
