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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
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
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        DelegationInfo info = delegation.get();
        String activeUntil = info.getActiveUntil() == null ? null
                : info.getActiveUntil().toInstant().atOffset(ZoneOffset.UTC).toLocalDate().toString();

        return Response.ok(new DelegationDto(info.getToUserKey(), activeUntil)).build();
    }

    @PUT
    public Response set(DelegationDto dto) {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (dto == null || dto.getToUserKey() == null || dto.getToUserKey().isBlank()) {
            return UserSettingsResource.badRequest("Поле toUserKey обязательно");
        }

        ApplicationUser delegate = userManager.getUserByKey(dto.getToUserKey());
        if (delegate == null) {
            return UserSettingsResource.notFound("Пользователь не найден: " + dto.getToUserKey());
        }

        Date activeUntil = null;
        if (dto.getActiveUntil() != null) {
            try {
                activeUntil = Date.from(
                        LocalDate.parse(dto.getActiveUntil()).atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception e) {
                return UserSettingsResource.badRequest("Неверный формат даты, ожидается YYYY-MM-DD");
            }
        }

        delegationService.setDelegation(user, delegate, activeUntil);
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
