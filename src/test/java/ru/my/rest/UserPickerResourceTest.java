package ru.my.rest;

import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class UserPickerResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private UserSearchService userSearchService;
    @Mock private UserManager userManager;

    private UserPickerResource resource;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        resource = new UserPickerResource(authContext, globalPermissionManager,
                userSearchService, userManager);
    }

    @Test
    public void searchReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.search("ан").getStatus());
    }

    /**
     * Без права «Browse users» поиск отдавал бы ключи и имена всех активных
     * пользователей любому авторизованному, включая клиентов Service Desk.
     */
    @Test
    public void searchReturns403WithoutUserPickerPermission() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.USER_PICKER, user))
                .thenReturn(false);

        assertEquals(403, resource.search("ан").getStatus());
        verify(userSearchService, never()).findUsers(anyString(), any());
    }

    @Test
    public void resolveReturns403WithoutUserPickerPermission() {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.USER_PICKER, user))
                .thenReturn(false);

        assertEquals(403, resource.resolve("JIRAUSER10100").getStatus());
        verify(userManager, never()).getUserByKey(anyString());
    }
}
