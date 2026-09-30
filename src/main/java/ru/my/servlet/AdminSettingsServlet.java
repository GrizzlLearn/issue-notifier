package ru.my.servlet;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.osgi.bridge.external.PluginRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Named
public class AdminSettingsServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsServlet.class);

    private final JiraAuthenticationContext authContext;
    private final GlobalPermissionManager globalPermissionManager;
    private final ProjectManager projectManager;
    private final ConstantsManager constantsManager;
    private final CustomFieldManager customFieldManager;
    /** Ключ плагина из манифеста бандла — из него строятся URL web-resource'ов. */
    private final String pluginKey;

    @Inject
    public AdminSettingsServlet(
            @ComponentImport JiraAuthenticationContext authContext,
            @ComponentImport GlobalPermissionManager globalPermissionManager,
            @ComponentImport ProjectManager projectManager,
            @ComponentImport ConstantsManager constantsManager,
            @ComponentImport CustomFieldManager customFieldManager,
            @ComponentImport PluginRetrievalService pluginRetrievalService) {
        this.authContext = authContext;
        this.globalPermissionManager = globalPermissionManager;
        this.projectManager = projectManager;
        this.constantsManager = constantsManager;
        this.customFieldManager = customFieldManager;
        this.pluginKey = pluginRetrievalService.getPlugin().getKey();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ApplicationUser user = authContext.getLoggedInUser();
        if (user == null) {
            resp.sendRedirect(req.getContextPath() + "/login.jsp?os_destination="
                    + URLEncoder.encode(req.getRequestURI(), StandardCharsets.UTF_8));
            return;
        }
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Требуются права администратора Jira");
            return;
        }

        log.debug("{} открыл админ-страницу плагина", user.getKey());

        // справочники страницы отдаём сразу в HTML — данные лежат в этом же
        // процессе Jira, ходить за ними из браузера незачем
        String pageData = AdminPageData.toJson(
                projectManager.getProjectObjects(),
                constantsManager.getStatuses(),
                customFieldManager.getCustomFieldObjects(),
                projectManager.getAllProjectCategories());

        String pluginResourceBase = req.getContextPath()
                + "/download/resources/" + pluginKey + ":admin-settings-resources";

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html>");
        out.println("<head>");
        out.println("  <title>Issue Notifier — Admin Settings</title>");
        out.println("  <meta name=\"decorator\" content=\"atl.admin\">");
        out.println("  <meta name=\"admin.active.section\" content=\"admin_plugins_menu\">");
        out.println("  <meta name=\"admin.active.tab\" content=\""
                + pluginKey + ":issue-notifier-admin-link\">");
        out.println("  <link rel=\"stylesheet\" href=\"" + pluginResourceBase + "/admin-settings.css\">");
        out.println("</head>");
        out.println("<body>");
        out.println("  <div id=\"issue-notifier-admin-root\"></div>");
        out.println("  " + AdminPageData.embed(pageData));
        out.println("  <script src=\"" + pluginResourceBase + "/admin-settings.js\"></script>");
        out.println("</body>");
        out.println("</html>");
    }
}
