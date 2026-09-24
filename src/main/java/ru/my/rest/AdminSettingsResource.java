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
import ru.my.impl.PortalProjects;
import ru.my.model.ActionScope;
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

    /** Проекты, отмеченные на вкладке «Проекты»: CSV из project key. */
    static final String SD_PROJECTS = PortalProjects.KEY;

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
                PortalProjects.CATEGORIES_KEY,
                ClosingStatuses.KEY));
        for (NotificationAction action : NotificationAction.values()) {
            keys.add(ActionTemplates.enabledKey(action));
            if (!action.isScopeFixed()) {
                keys.add(ActionTemplates.scopeKey(action));
            }
            if (action.isRecipientsConfigurable()) {
                keys.add(ActionTemplates.recipientsKey(action));
            }
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
            if (!action.isScopeFixed()) {
                keys.add(ActionTemplates.scopeKey(action));
            }
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
                settings.put(key, adminSettingsService.get(key, defaultFor(key)));
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
            Response invalidScope = validateScope(e.getKey(), e.getValue());
            if (invalidScope != null) {
                return invalidScope;
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
    /**
     * Значение по умолчанию для ключа, которого нет в базе.
     * <p>
     * Булев ключ без записи — это «выключено», а не «пусто»: клиент отправляет
     * полученное значение обратно, и {@code ""} не прошло бы валидацию PUT.
     * Ключ области отдаёт область действия по умолчанию — иначе переключатель
     * на странице не показывал бы реального поведения.
     */
    private static String defaultFor(String key) {
        if (BOOLEAN_KEYS.contains(key)) {
            return "false";
        }
        NotificationAction action = ActionTemplates.actionOfScopeKey(key);
        return action != null ? action.defaultScope().key() : "";
    }

    /** Область действия принимает только {@code "all"} или {@code "selected"}. */
    private static Response validateScope(String key, String value) {
        if (ActionTemplates.actionOfScopeKey(key) == null || value == null || value.isBlank()) {
            return null;
        }
        if (ActionScope.byKey(value) != null) {
            return null;
        }
        return UserSettingsResource.badRequest(
                "Недопустимая область для '" + key + "': ожидается 'all' или 'selected'");
    }

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
