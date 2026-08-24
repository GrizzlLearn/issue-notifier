package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.my.api.MessageFormatter;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import javax.inject.Named;

@Named
@ExportAsService(MessageFormatter.class)
public class EmailMessageFormatter implements MessageFormatter {

    @Override
    public String format(Issue issue, DiffResult diff) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<html><body style=\"font-family:Arial,sans-serif;font-size:14px;\">")
          .append("<p><strong>").append(esc(issue.getKey())).append("</strong>")
          .append(" — ").append(esc(issue.getSummary())).append("</p>")
          .append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;\">")
          .append("<tr style=\"background:#f5f5f5;\"><th>Поле</th><th>Было</th><th>Стало</th></tr>");

        for (DiffResult.FieldChange c : diff.getChanges()) {
            sb.append("<tr>")
              .append("<td>").append(esc(c.fieldName())).append("</td>")
              .append("<td>").append(esc(c.fromValue())).append("</td>")
              .append("<td>").append(esc(c.toValue())).append("</td>")
              .append("</tr>");
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
