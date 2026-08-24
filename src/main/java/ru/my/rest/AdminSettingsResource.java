package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.impl.ChannelKeys;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Named
@Path("/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSettingsResource {

    static final List<String> KNOWN_KEYS = List.of(
            "email.enabled",
            "mattermost.enabled",
            ChannelKeys.MATTERMOST_DOMAIN,
            ChannelKeys.MATTERMOST_TOKEN,
            ChannelKeys.MATTERMOST_BOT_ID,
            "telegram.enabled",
            ChannelKeys.TELEGRAM_BOT_TOKEN
    );

    // ключи, содержащие секреты — маскируются в GET, не перезаписываются маской в PUT
    // mattermost.botId — публичный идентификатор, не секрет, маскировать не нужно
    static final Set<String> SECRETS = Set.of(ChannelKeys.MATTERMOST_TOKEN, ChannelKeys.TELEGRAM_BOT_TOKEN);
    private static final String MASKED = "***";

    // ключи с булевой семантикой — принимают только "true" или "false"
    static final Set<String> BOOLEAN_KEYS = Set.of("email.enabled", "mattermost.enabled", "telegram.enabled");

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
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();

        Map<String, String> settings = KNOWN_KEYS.stream()
                .collect(Collectors.toMap(k -> k, k -> maskIfSecret(k, adminSettingsService.get(k, ""))));

        return Response.ok(settings).build();
    }

    /**
     * Сохраняет настройки плагина. Возвращает {@code 204} при успехе.
     * <p>
     * Важно: {@code 204} не гарантирует запись всех переданных ключей —
     * неизвестные ключи (не из {@link #KNOWN_KEYS}) молча игнорируются,
     * секретные поля с пустым значением или маской {@code "***"} не перезаписываются,
     * булевы поля с недопустимым значением возвращают {@code 400}.
     */
    @PUT
    public Response set(Map<String, String> body) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();
        if (body == null || body.isEmpty()) return UserSettingsResource.badRequest("Тело запроса не задано");

        // B2: валидация булевых ключей до сохранения
        for (Map.Entry<String, String> e : body.entrySet()) {
            if (BOOLEAN_KEYS.contains(e.getKey())
                    && !"true".equals(e.getValue()) && !"false".equals(e.getValue())) {
                return UserSettingsResource.badRequest(
                        "Недопустимое значение для '" + e.getKey() + "': ожидается 'true' или 'false'");
            }
        }

        body.entrySet().stream()
                .filter(e -> KNOWN_KEYS.contains(e.getKey()))
                .filter(e -> !isBlankOrMaskedSecret(e.getKey(), e.getValue()))
                .forEach(e -> adminSettingsService.set(e.getKey(), e.getValue()));

        return Response.noContent().build();
    }

    private String maskIfSecret(String key, String value) {
        return SECRETS.contains(key) && !value.isBlank() ? MASKED : value;
    }

    private boolean isBlankOrMaskedSecret(String key, String value) {
        if (!SECRETS.contains(key)) return false; // несекретные поля (domain, enabled) очищать можно
        return value == null || value.isBlank() || MASKED.equals(value);
    }
}
