package ru.my.rest;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.DelegationService;
import ru.my.model.DelegationInfo;

import javax.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class UserDelegationResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private UserManager userManager;
    @Mock private DelegationService delegationService;

    private UserDelegationResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");
    private final MockApplicationUser delegate = new MockApplicationUser("bob");

    @Before
    public void setUp() {
        resource = new UserDelegationResource(authContext, userManager, delegationService);
    }

    // --- GET ---

    @Test
    public void getReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.get().getStatus());
    }

    @Test
    public void getReturns404WhenNoDelegation() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(delegationService.getDelegation(user)).thenReturn(Optional.empty());
        assertEquals(404, resource.get().getStatus());
    }

    @Test
    public void getReturnsDelegationWithDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        Date until = Date.from(LocalDate.of(2026, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant());
        when(delegationService.getDelegation(user)).thenReturn(Optional.of(new DelegationInfo("bob", until)));

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        DelegationDto dto = (DelegationDto) response.getEntity();
        assertEquals("bob", dto.getToUserKey());
        assertEquals("2026-12-31", dto.getActiveUntil());
    }

    @Test
    public void getReturnsDelegationWithNullDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(delegationService.getDelegation(user)).thenReturn(Optional.of(new DelegationInfo("bob", null)));

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        DelegationDto dto = (DelegationDto) response.getEntity();
        assertNull(dto.getActiveUntil());
    }

    // --- PUT ---

    @Test
    public void putReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.set(new DelegationDto("bob", null)).getStatus());
    }

    @Test
    public void putReturns400WhenToUserKeyIsBlank() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        assertEquals(400, resource.set(new DelegationDto("", null)).getStatus());
        assertEquals(400, resource.set(new DelegationDto(null, null)).getStatus());
    }

    @Test
    public void putReturns404WhenDelegateNotFound() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("unknown")).thenReturn(null);
        assertEquals(404, resource.set(new DelegationDto("unknown", null)).getStatus());
    }

    @Test
    public void putSetsDelegationWithoutDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(delegate);

        Response response = resource.set(new DelegationDto("bob", null));

        assertEquals(204, response.getStatus());
        verify(delegationService).setDelegation(user, delegate, null);
    }

    @Test
    public void putSetsDelegationWithDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(delegate);

        Response response = resource.set(new DelegationDto("bob", "2026-09-01"));

        assertEquals(204, response.getStatus());
        verify(delegationService).setDelegation(eq(user), eq(delegate), notNull());
    }

    @Test
    public void putReturns400WhenDateFormatIsInvalid() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(delegate);
        assertEquals(400, resource.set(new DelegationDto("bob", "31.12.2026")).getStatus());
    }

    // --- DELETE ---

    @Test
    public void deleteReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.remove().getStatus());
    }

    @Test
    public void deleteRemovesDelegationAndReturns204() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        Response response = resource.remove();
        assertEquals(204, response.getStatus());
        verify(delegationService).removeDelegation(user);
    }

    private static void assertNull(Object o) {
        assertEquals(null, o);
    }
}
