package ru.my.servlet;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Проверяет охрану админ-страницы: без логина — редирект, без прав администратора —
 * 403, и только администратору отдаётся HTML со справочниками.
 */
@RunWith(MockitoJUnitRunner.class)
public class AdminSettingsServletTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private ProjectManager projectManager;
    @Mock private ConstantsManager constantsManager;
    @Mock private CustomFieldManager customFieldManager;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private AdminSettingsServlet servlet;
    private final MockApplicationUser user = new MockApplicationUser("jdoe");
    private final StringWriter body = new StringWriter();

    @Before
    public void setUp() {
        servlet = new AdminSettingsServlet(authContext, globalPermissionManager,
                projectManager, constantsManager, customFieldManager);
    }

    @Test
    public void redirectsToLoginWhenNotLoggedIn() throws IOException {
        when(authContext.getLoggedInUser()).thenReturn(null);
        when(request.getContextPath()).thenReturn("/jira");
        when(request.getRequestURI()).thenReturn("/jira/plugins/servlet/issue-notifier/admin");

        servlet.doGet(request, response);

        verify(response).sendRedirect(contains("/jira/login.jsp"));
        verify(response, never()).getWriter();
    }

    @Test
    public void returns403ForNonAdmin() throws IOException {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)).thenReturn(false);

        servlet.doGet(request, response);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        verify(response, never()).getWriter();
        verifyNoInteractions(projectManager);
    }

    @Test
    public void rendersPageForAdmin() throws IOException {
        when(authContext.getLoggedInUser()).thenReturn(user);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)).thenReturn(true);
        when(request.getContextPath()).thenReturn("/jira");
        when(projectManager.getProjectObjects()).thenReturn(List.of());
        when(projectManager.getAllProjectCategories()).thenReturn(List.of());
        when(constantsManager.getStatuses()).thenReturn(List.of());
        when(customFieldManager.getCustomFieldObjects()).thenReturn(List.of());
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        servlet.doGet(request, response);

        String html = body.toString();
        assertTrue(html.contains("issue-notifier-admin-root"));
        assertTrue(html.contains("window.ISSUE_NOTIFIER_DATA"));
    }
}
