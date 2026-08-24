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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Named
@Path("/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSettingsResource {

    /**
     * Суффикс read-only ключа, который показывает задан ли соответствующий секрет.
     * Например, {@code "mattermost.token.isSet"} → {@code "true"} / {@code "false"}.
     * Сам секретный ключ ({@code "mattermost.token"}) в GET всегда возвращается пустым
     * и принимается в PUT только если значение непустое (write-only семантика).
     */
    static final String IS_SET_SUFFIX = ".isSet";

    static final List<String> KNOWN_KEYS = List.of(
            "email.enabled",
            "mattermost.enabled",
            ChannelKeys.MATTERMOST_DOMAIN,
            ChannelKeys.MATTERMOST_BOT_ID,
            ChannelKeys.MATTERMOST_TOKEN,
            ChannelKeys.MATTERMOST_TOKEN + IS_SET_SUFFIX,
            "telegram.enabled",
            ChannelKeys.TELEGRAM_BOT_USERNAME,
            ChannelKeys.TELEGRAM_BOT_TOKEN,
            ChannelKeys.TELEGRAM_BOT_TOKEN + IS_SET_SUFFIX
    );

    // секретные ключи — write-only: GET возвращает "", PUT сохраняет только непустое значение
    static final Set<String> SECRETS = Set.of(
            ChannelKeys.MATTERMOST_TOKEN,
            ChannelKeys.TELEGRAM_BOT_TOKEN
    );

    // ключи с булевой семантикой — принимают только "true" или "false"
    static final Set<String> BOOLEAN_KEYS = Set.of(
            "email.enabled", "mattermost.enabled", "telegram.enabled"
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
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();

        Map<String, String> settings = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            if (SECRETS.contains(key)) {
                // write-only: никогда не возвращаем реальное значение
                settings.put(key, "");
            } else if (isIsSetKey(key)) {
                // производный ключ: читаем реальный секрет и возвращаем факт его наличия
                String secretKey = key.substring(0, key.length() - IS_SET_SUFFIX.length());
                boolean isSet = !adminSettingsService.get(secretKey, "").isBlank();
                settings.put(key, String.valueOf(isSet));
            } else {
                settings.put(key, adminSettingsService.get(key, ""));
            }
        }
        return Response.ok(settings).build();
    }

    /**
     * Сохраняет настройки плагина. Возвращает {@code 204} при успехе.
     * <p>
     * Правила фильтрации входящих ключей:
     * <ul>
     *   <li>неизвестные ключи — молча игнорируются;</li>
     *   <li>{@code .isSet} ключи — read-only, игнорируются;</li>
     *   <li>секретные ключи с пустым значением — не перезаписывают существующий секрет;</li>
     *   <li>булевы ключи с недопустимым значением — возвращают {@code 400}.</li>
     * </ul>
     */
    @PUT
    public Response set(Map<String, String> body) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();
        if (body == null || body.isEmpty()) return UserSettingsResource.badRequest("Тело запроса не задано");

        for (Map.Entry<String, String> e : body.entrySet()) {
            if (BOOLEAN_KEYS.contains(e.getKey())
                    && !"true".equals(e.getValue()) && !"false".equals(e.getValue())) {
                return UserSettingsResource.badRequest(
                        "Недопустимое значение для '" + e.getKey() + "': ожидается 'true' или 'false'");
            }
        }

        body.entrySet().stream()
                .filter(e -> KNOWN_KEYS.contains(e.getKey()))
                .filter(e -> !isIsSetKey(e.getKey()))                                      // read-only
                .filter(e -> !SECRETS.contains(e.getKey()) || !e.getValue().isBlank())     // пустой секрет — не трогаем
                .forEach(e -> adminSettingsService.set(e.getKey(), e.getValue()));

        return Response.noContent().build();
    }

    private static boolean isIsSetKey(String key) {
        String candidate = key.endsWith(IS_SET_SUFFIX)
                ? key.substring(0, key.length() - IS_SET_SUFFIX.length())
                : null;
        return candidate != null && SECRETS.contains(candidate);
    }
}
