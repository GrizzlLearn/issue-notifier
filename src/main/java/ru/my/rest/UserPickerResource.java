package ru.my.rest;

import com.atlassian.jira.bc.user.search.UserSearchParams;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Поиск/резолв пользователей для пикера делегирования.
 * <p>
 * Бэкенд отдаёт готовые {@code {value,label}} через {@link UserSearchService} —
 * тот же сервис, что используют нативные user-picker поля Jira. Фронтенд не ходит
 * в REST API самой Jira напрямую.
 */
@Named
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserPickerResource {

    private static final int MAX_RESULTS = 20;

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;
    private final UserSearchService userSearchService;
    private final UserManager userManager;

    @Inject
    public UserPickerResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager,
            @ComponentImport UserSearchService userSearchService,
            @ComponentImport UserManager userManager) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
        this.userSearchService = userSearchService;
        this.userManager = userManager;
    }

    @GET
    public Response search(@QueryParam("query") String query) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();

        UserSearchParams params = UserSearchParams.builder()
                .allowEmptyQuery(true)
                .includeActive(true)
                .includeInactive(false)
                .maxResults(MAX_RESULTS)
                .build();

        String q = query == null ? "" : query;
        List<PickerItemDto> results = userSearchService.findUsers(q, params).stream()
                .map(u -> new PickerItemDto(u.getKey(), u.getDisplayName()))
                .collect(Collectors.toList());

        return Response.ok(results).build();
    }

    /**
     * Резолв одного ключа в имя. Право «Browse users» проверяется так же, как в
     * {@link #search}: иначе перебор ключей отдавал бы имена всех пользователей
     * тому, кому поиск по ним закрыт.
     */
    @GET
    @Path("/{key}")
    public Response resolve(@PathParam("key") String key) {
        ApplicationUser current = authContext.getLoggedInUser();
        if (current == null) return UserSettingsResource.unauthorized();
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.USER_PICKER, current)) {
            return UserSettingsResource.forbidden();
        }

        ApplicationUser target = userManager.getUserByKey(key);
        if (target == null) {
            return UserSettingsResource.notFound("Пользователь не найден: " + key);
        }
        return Response.ok(new PickerItemDto(target.getKey(), target.getDisplayName())).build();
    }
}
