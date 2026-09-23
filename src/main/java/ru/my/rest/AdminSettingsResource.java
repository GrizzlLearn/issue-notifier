package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.impl.ActionTemplates;
import ru.my.impl.ChannelKeys;
import ru.my.impl.ClosingStatuses;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** Проекты, к которым применяется логика портала (SD и обычные): CSV из project key. */
    static final String SD_PROJECTS = "sd.projects";

    /** Каналы, по которым рассылаются уведомления о действиях (email не участвует). */
    private static final List<NotificationChannel> ACTION_CHANNELS =
            List.of(NotificationChannel.MATTERMOST, NotificationChannel.TELEGRAM);

    static final List<String> KNOWN_KEYS = buildKnownKeys();

    /**
     * Постоянные ключи плюс ключи каталога действий — они генерируются из
     * {@link NotificationAction}, чтобы новое действие не требовало правок здесь.
     */
    private static List<String> buildKnownKeys() {
        List<String> keys = new ArrayList<>(List.of(
                "email.enabled",
                "mattermost.enabled",
                ChannelKeys.MATTERMOST_DOMAIN,
                ChannelKeys.MATTERMOST_BOT_ID,
                ChannelKeys.MATTERMOST_TOKEN,
                ChannelKeys.MATTERMOST_TOKEN + IS_SET_SUFFIX,
                "telegram.enabled",
                ChannelKeys.TELEGRAM_BOT_USERNAME,
                ChannelKeys.TELEGRAM_BOT_TOKEN,
                ChannelKeys.TELEGRAM_BOT_TOKEN + IS_SET_SUFFIX,
                SD_PROJECTS,
                ClosingStatuses.KEY));
        for (NotificationAction action : NotificationAction.values()) {
            keys.add(ActionTemplates.enabledKey(action));
            for (NotificationChannel channel : ACTION_CHANNELS) {
                keys.add(ActionTemplates.templateKey(action, channel));
            }
        }
        return List.copyOf(keys);
    }

    // секретные ключи — write-only: GET возвращает "", PUT сохраняет только непустое значение
    static final Set<String> SECRETS = Set.of(
            ChannelKeys.MATTERMOST_TOKEN,
            ChannelKeys.TELEGRAM_BOT_TOKEN
    );

    // ключи с булевой семантикой — принимают только "true" или "false"
    static final Set<String> BOOLEAN_KEYS = buildBooleanKeys();

    private static Set<String> buildBooleanKeys() {
        Set<String> keys = new LinkedHashSet<>(Set.of(
                "email.enabled", "mattermost.enabled", "telegram.enabled"));
        for (NotificationAction action : NotificationAction.values()) {
            keys.add(ActionTemplates.enabledKey(action));
        }
        return Set.copyOf(keys);
    }

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
                // булев ключ без записи в базе — это выключено, а не «пусто»:
                // клиент отправляет полученное значение обратно, и "" не прошло бы валидацию PUT
                settings.put(key, adminSettingsService.get(key, BOOLEAN_KEYS.contains(key) ? "false" : ""));
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
            if (isBlankBoolean(e.getKey(), e.getValue())) {
                continue;
            }
            if (BOOLEAN_KEYS.contains(e.getKey())
                    && !"true".equals(e.getValue()) && !"false".equals(e.getValue())) {
                return UserSettingsResource.badRequest(
                        "Недопустимое значение для '" + e.getKey() + "': ожидается 'true' или 'false'");
            }
            Response invalidTemplate = validateTemplate(e.getKey(), e.getValue());
            if (invalidTemplate != null) {
                return invalidTemplate;
            }
        }

        body.entrySet().stream()
                .filter(e -> KNOWN_KEYS.contains(e.getKey()))
                .filter(e -> !isBlankBoolean(e.getKey(), e.getValue()))                   // «не задано» — не трогаем
                .filter(e -> !isIsSetKey(e.getKey()))                                      // read-only
                .filter(e -> !SECRETS.contains(e.getKey()) || !e.getValue().isBlank())     // пустой секрет — не трогаем
                .forEach(e -> adminSettingsService.set(e.getKey(), e.getValue()));

        return Response.noContent().build();
    }

    /**
     * Проверяет шаблон действия на неизвестные плейсхолдеры: опечатка вроде
     * {@code {issuekey}} иначе молча уйдёт получателю в сыром виде.
     *
     * @return ответ 400 при ошибке или {@code null}, если проверять нечего
     */
    private static Response validateTemplate(String key, String value) {
        NotificationAction action = ActionTemplates.actionOfTemplateKey(key);
        if (action == null || value == null || value.isBlank()) {
            return null;
        }
        List<String> unknown = ActionTemplates.unknownPlaceholders(value, action);
        if (unknown.isEmpty()) {
            return null;
        }
        return UserSettingsResource.badRequest(
                "Неизвестные плейсхолдеры в шаблоне '" + key + "': " + String.join(", ", unknown)
                        + ". Допустимые: " + String.join(", ", action.placeholders()));
    }

    /**
     * Пустое значение булевого ключа — «настройка не задана»: так его отдавали
     * старые версии GET, и страница, открытая до обновления плагина, шлёт его обратно.
     * Такой ключ пропускаем молча вместо 400.
     */
    private static boolean isBlankBoolean(String key, String value) {
        return BOOLEAN_KEYS.contains(key) && (value == null || value.isBlank());
    }

    private static boolean isIsSetKey(String key) {
        String candidate = key.endsWith(IS_SET_SUFFIX)
                ? key.substring(0, key.length() - IS_SET_SUFFIX.length())
                : null;
        return candidate != null && SECRETS.contains(candidate);
    }
}
