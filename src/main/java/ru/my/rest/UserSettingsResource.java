package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.AdminSettingsService;
import ru.my.api.UserSettingsService;
import ru.my.model.ActionTemplates;
import ru.my.model.ChannelKeys;
import ru.my.model.CommentTextMode;
import ru.my.model.NotificationChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(UserSettingsResource.class);

    /**
     * Telegram chat_id — целое число, у групп со знаком минус. Поле правит
     * пользователь руками, и мусор в нём иначе виден только в логах отправки.
     */
    private static final Pattern CHAT_ID = Pattern.compile("-?\\d{1,20}");

    /** Разумный предел для списка проектов: поле хранится одной строкой в AO. */
    static final int MAX_PROJECTS = 500;

    /**
     * Ключ проекта Jira: буква, дальше буквы, цифры и подчёркивание. Звёздочка —
     * «все проекты». Предела на количество недостаточно: список склеивается
     * в колонку без ограничения длины, и без этой проверки любой авторизованный
     * пишет в хранилище пятьсот строк произвольного размера.
     * <p>
     * Длина ограничена щедро, а не десятью символами по умолчанию Jira: шаблон
     * ключа настраивается администратором (`jira.projectkey.pattern`), и на
     * инстансе с расширенным шаблоном жёсткий предел отбивал бы сохранение
     * настроек целиком — интерфейс присылает обратно весь сохранённый список.
     */
    private static final Pattern PROJECT_KEY = Pattern.compile("\\*|[A-Za-z][A-Za-z0-9_]{0,254}");

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
        log.debug("{} открыл свои настройки уведомлений", user.getKey());
        String botUsername = adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_USERNAME, "");
        List<String> enabledChannels = Arrays.stream(NotificationChannel.values())
                .filter(adminSettingsService::isChannelEnabled)
                .map(NotificationChannel::name)
                .collect(Collectors.toList());
        CommentTextMode commentTextMode = CommentTextMode.resolve(
                adminSettingsService.get(CommentTextMode.KEY, ""),
                adminSettingsService.get(ActionTemplates.HIDE_COMMENT_TEXT_KEY, "false"));
        boolean watchersEnabled = Boolean.parseBoolean(
                adminSettingsService.get(ActionTemplates.WATCHERS_ENABLED_KEY, "false"));
        return Response.ok(UserSettingsDto.from(
                userSettingsService.getSettings(user), botUsername, enabledChannels,
                commentTextMode, watchersEnabled)).build();
    }

    @PUT
    public Response save(UserSettingsDto dto) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return unauthorized();
        if (dto == null) return badRequest("Тело запроса не задано");
        log.debug("{} сохраняет свои настройки уведомлений", user.getKey());

        String chatId = dto.getTelegramChatId();
        if (chatId != null && !chatId.isBlank() && !CHAT_ID.matcher(chatId.trim()).matches()) {
            return badRequest("Telegram chat_id — это число; его присылает бот в ответ на /start");
        }
        // список приходит с вкладки «Наблюдение»; предел нужен и потому, что REST
        // открыт любому авторизованному клиенту, а не только нашему фронтенду
        if (dto.getProjects() != null && dto.getProjects().size() > MAX_PROJECTS) {
            return badRequest("Слишком много проектов: не больше " + MAX_PROJECTS);
        }
        if (dto.getProjects() != null) {
            for (String key : dto.getProjects()) {
                if (key == null || !PROJECT_KEY.matcher(key).matches()) {
                    return badRequest("Это не похоже на ключ проекта: " + abbreviate(key));
                }
            }
        }
        // пустой список — состояние «ни все проекты, ни выбранные». Хранить его нельзя:
        // в БД он ложится пустой строкой, а она при чтении означает «все проекты»,
        // то есть настройка молча превратилась бы в свою противоположность.
        // Отсутствующее поле (null) — другое дело: это «не меняли», и там подставляется "*"
        if (dto.getProjects() != null && dto.getProjects().isEmpty()) {
            return badRequest("Выберите хотя бы один проект или отметьте «Все проекты»");
        }

        userSettingsService.saveSettings(user, dto.toModel());
        return Response.noContent().build();
    }

    /** В ответ не возвращаем присланное целиком: оно могло быть килобайтами мусора. */
    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 20 ? value : value.substring(0, 20) + "…";
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
