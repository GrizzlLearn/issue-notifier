package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.my.impl.EventIntakeDiagnostics;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Named
@Path("/admin/intake-diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class EventIntakeDiagnosticsResource {
    private final JiraAuthenticationContext auth;
    private final GlobalPermissionManager globals;
    private final EventIntakeDiagnostics diagnostics;

    @Inject
    public EventIntakeDiagnosticsResource(@ComponentImport JiraAuthenticationContext auth,
                                          @ComponentImport GlobalPermissionManager globals,
                                          EventIntakeDiagnostics diagnostics) {
        this.auth = auth;
        this.globals = globals;
        this.diagnostics = diagnostics;
    }

    @GET
    public Response get() {
        ApplicationUser user = auth.getLoggedInUser();
        if (user == null) return UserSettingsResource.unauthorized();
        if (!globals.hasPermission(GlobalPermissionKey.ADMINISTER, user)) return UserSettingsResource.forbidden();
        return Response.ok(diagnostics.snapshot()).build();
    }
}
