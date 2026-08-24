package ru.my.servlet;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class AdminSettingsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JiraAuthenticationContext authContext = ComponentAccessor.getComponent(JiraAuthenticationContext.class);
        GlobalPermissionManager gpm = ComponentAccessor.getComponent(GlobalPermissionManager.class);

        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) {
            resp.sendRedirect(req.getContextPath() + "/login.jsp?os_destination=" + req.getRequestURI());
            return;
        }
        if (!gpm.hasPermission(GlobalPermissionKey.ADMINISTER, user)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Требуются права администратора Jira");
            return;
        }

        String pluginResourceBase = req.getContextPath()
                + "/download/resources/ru.my.issue-notifier:admin-settings-resources";

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html>");
        out.println("<head>");
        out.println("  <title>Issue Notifier — Admin Settings</title>");
        out.println("  <meta name=\"decorator\" content=\"atl.admin\">");
        out.println("  <meta name=\"admin.active.section\" content=\"admin_plugins_menu\">");
        out.println("  <meta name=\"admin.active.tab\" content=\"issue-notifier-admin-link\">");
        out.println("</head>");
        out.println("<body>");
        out.println("  <div id=\"issue-notifier-admin-root\"></div>");
        out.println("  <script src=\"" + pluginResourceBase + "/admin-settings.js\"></script>");
        out.println("</body>");
        out.println("</html>");
    }
}
