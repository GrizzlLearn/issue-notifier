package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Test;
import ru.my.impl.EventIntakeDiagnostics;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EventIntakeDiagnosticsResourceTest {
    @Test
    public void requiresAdminAndReturnsLocalDiagnostics() {
        var auth = mock(JiraAuthenticationContext.class);
        var globals = mock(GlobalPermissionManager.class);
        var diagnostics = mock(EventIntakeDiagnostics.class);
        var resource = new EventIntakeDiagnosticsResource(auth, globals, diagnostics);
        assertEquals(401, resource.get().getStatus());
        var user = new MockApplicationUser("admin");
        when(auth.getLoggedInUser()).thenReturn(user);
        assertEquals(403, resource.get().getStatus());
        verifyNoInteractions(diagnostics);
        when(globals.hasPermission(GlobalPermissionKey.ADMINISTER, user)).thenReturn(true);
        var snapshot = new EventIntakeDiagnostics().snapshot();
        when(diagnostics.snapshot()).thenReturn(snapshot);
        assertSame(snapshot, resource.get().getEntity());
    }
}
