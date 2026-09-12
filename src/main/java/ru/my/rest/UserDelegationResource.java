package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.api.DelegationService;
import ru.my.model.DelegationInfo;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Named
@Path("/user/delegation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserDelegationResource {

    private final JiraAuthenticationContext authContext;
    private final DelegationService delegationService;
    private final UserManager userManager;

    @Inject
    public UserDelegationResource(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport UserManager userManager,
            DelegationService delegationService) {
        this.authContext = authContext;
        this.userManager = userManager;
        this.delegationService = delegationService;
    }

    @GET
    public Response get() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();

        Optional<DelegationInfo> delegation = delegationService.getDelegation(user);
        if (delegation.isEmpty()) {
            return Response.ok(new DelegationDto(List.of(), null)).build();
        }

        DelegationInfo info = delegation.get();
        Instant activeUntil = info.getActiveUntil();
        String activeUntilStr = activeUntil == null ? null
                : activeUntil.atOffset(ZoneOffset.UTC).toLocalDate().toString();

        return Response.ok(new DelegationDto(info.getToUserKeys(), activeUntilStr)).build();
    }

    @PUT
    public Response set(DelegationDto dto) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (dto == null || dto.getToUserKeys() == null || dto.getToUserKeys().isEmpty()) {
            return UserSettingsResource.badRequest("Поле toUserKeys обязательно и не может быть пустым");
        }

        List<ApplicationUser> delegates = new ArrayList<>();
        for (String key : dto.getToUserKeys()) {
            ApplicationUser delegate = userManager.getUserByKey(key);
            if (delegate == null) {
                return UserSettingsResource.notFound("Пользователь не найден: " + key);
            }
            delegates.add(delegate);
        }

        Instant activeUntil = null;
        if (dto.getActiveUntil() != null) {
            try {
                activeUntil = LocalDate.parse(dto.getActiveUntil()).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception e) {
                return UserSettingsResource.badRequest("Неверный формат даты, ожидается YYYY-MM-DD");
            }
        }

        try {
            delegationService.setDelegation(user, delegates, activeUntil);
        } catch (IllegalArgumentException e) {
            return UserSettingsResource.badRequest(e.getMessage());
        }
        return Response.noContent().build();
    }

    @DELETE
    public Response remove() {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        delegationService.removeDelegation(user);
        return Response.noContent().build();
    }
}
