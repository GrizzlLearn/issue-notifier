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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class UserDelegationResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private UserManager userManager;
    @Mock private DelegationService delegationService;

    private UserDelegationResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");
    private final MockApplicationUser bob = new MockApplicationUser("bob");
    private final MockApplicationUser carol = new MockApplicationUser("carol");

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
    public void getReturnsEmptyDtoWhenNoDelegation() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(delegationService.getDelegation(user)).thenReturn(Optional.empty());

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        DelegationDto dto = (DelegationDto) response.getEntity();
        assertEquals(List.of(), dto.getToUserKeys());
        assertNull(dto.getActiveUntil());
    }

    @Test
    public void getReturnsDelegationWithDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        Instant until = LocalDate.of(2026, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant();
        when(delegationService.getDelegation(user)).thenReturn(Optional.of(new DelegationInfo(List.of("bob"), until)));

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        DelegationDto dto = (DelegationDto) response.getEntity();
        assertEquals(List.of("bob"), dto.getToUserKeys());
        assertEquals("2026-12-31", dto.getActiveUntil());
    }

    @Test
    public void getReturnsMultipleDelegates() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(delegationService.getDelegation(user))
                .thenReturn(Optional.of(new DelegationInfo(List.of("bob", "carol"), null)));

        Response response = resource.get();

        DelegationDto dto = (DelegationDto) response.getEntity();
        assertEquals(List.of("bob", "carol"), dto.getToUserKeys());
    }

    @Test
    public void getReturnsDelegationWithNullDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(delegationService.getDelegation(user)).thenReturn(Optional.of(new DelegationInfo(List.of("bob"), null)));

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        DelegationDto dto = (DelegationDto) response.getEntity();
        assertNull(dto.getActiveUntil());
    }

    // --- PUT ---

    @Test
    public void putReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.set(new DelegationDto(List.of("bob"), null)).getStatus());
    }

    @Test
    public void putReturns400WhenToUserKeysIsEmpty() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        assertEquals(400, resource.set(new DelegationDto(List.of(), null)).getStatus());
        assertEquals(400, resource.set(new DelegationDto(null, null)).getStatus());
    }

    @Test
    public void putReturns404WhenDelegateNotFound() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("unknown")).thenReturn(null);
        assertEquals(404, resource.set(new DelegationDto(List.of("unknown"), null)).getStatus());
    }

    @Test
    public void putReturns404WhenOneOfSeveralDelegatesNotFound() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(bob);
        when(userManager.getUserByKey("unknown")).thenReturn(null);
        assertEquals(404, resource.set(new DelegationDto(List.of("bob", "unknown"), null)).getStatus());
    }

    @Test
    public void putSetsDelegationWithoutDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(bob);

        Response response = resource.set(new DelegationDto(List.of("bob"), null));

        assertEquals(204, response.getStatus());
        verify(delegationService).setDelegation(user, List.of(bob), null);
    }

    @Test
    public void putSetsDelegationWithMultipleDelegates() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(bob);
        when(userManager.getUserByKey("carol")).thenReturn(carol);

        Response response = resource.set(new DelegationDto(List.of("bob", "carol"), null));

        assertEquals(204, response.getStatus());
        verify(delegationService).setDelegation(user, List.of(bob, carol), null);
    }

    @Test
    public void putSetsDelegationWithDate() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(bob);

        Response response = resource.set(new DelegationDto(List.of("bob"), "2026-09-01"));

        assertEquals(204, response.getStatus());
        verify(delegationService).setDelegation(eq(user), eq(List.of(bob)), notNull());
    }

    @Test
    public void putReturns400WhenDateFormatIsInvalid() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("bob")).thenReturn(bob);
        assertEquals(400, resource.set(new DelegationDto(List.of("bob"), "31.12.2026")).getStatus());
    }

    @Test
    public void putReturns400WhenServiceRejectsSelfDelegation() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(userManager.getUserByKey("jdoe")).thenReturn(user);
        doThrow(new IllegalArgumentException("Нельзя делегировать уведомления самому себе"))
                .when(delegationService).setDelegation(eq(user), eq(List.of(user)), any());

        assertEquals(400, resource.set(new DelegationDto(List.of("jdoe"), null)).getStatus());
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
}
