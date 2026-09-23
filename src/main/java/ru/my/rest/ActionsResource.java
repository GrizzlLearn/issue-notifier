package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.impl.ActionTemplates;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Метаданные каталога действий для вкладки «Действия» в настройках плагина:
 * название, ключи настроек, допустимые плейсхолдеры и тексты по умолчанию.
 * <p>
 * Значения самих настроек отдаёт и принимает {@link AdminSettingsResource} —
 * здесь только справочник, чтобы названия действий и дефолтные шаблоны
 * не дублировались в JS.
 */
@Named
@Path("/admin/actions")
@Produces(MediaType.APPLICATION_JSON)
public class ActionsResource {

    /** Каналы, для которых у действий есть шаблоны (email в рассылке действий не участвует). */
    private static final List<NotificationChannel> CHANNELS =
            List.of(NotificationChannel.MATTERMOST, NotificationChannel.TELEGRAM);

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;

    @Inject
    public ActionsResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
    }

    @GET
    public Response list() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();

        List<Map<String, Object>> result = new ArrayList<>();
        for (NotificationAction action : NotificationAction.values()) {
            // Map вместо DTO — Jackson сериализует его без @JsonProperty на каждом поле
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", action.key());
            item.put("title", action.title());
            item.put("placeholders", action.placeholders());
            item.put("enabledKey", ActionTemplates.enabledKey(action));

            List<Map<String, String>> channels = new ArrayList<>();
            for (NotificationChannel channel : CHANNELS) {
                Map<String, String> ch = new LinkedHashMap<>();
                ch.put("channel", channel.name());
                ch.put("templateKey", ActionTemplates.templateKey(action, channel));
                channels.add(ch);
            }
            item.put("channels", channels);
            result.add(item);
        }
        return Response.ok(result).build();
    }
}
