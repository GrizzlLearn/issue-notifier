package ru.my.impl.email;

import com.atlassian.jira.issue.Issue;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.my.api.MessageFormatter;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import ru.my.impl.util.TextDiff;
import javax.inject.Named;
import java.util.List;

@Named
@ExportAsService(MessageFormatter.class)
public class EmailMessageFormatter implements MessageFormatter {

    /** Суммарная длина from+to, при превышении которой используется diff-вид. */
    static final int DIFF_THRESHOLD = 300;

    @Override
    public String format(Issue issue, DiffResult diff) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<html><body style=\"font-family:Arial,sans-serif;font-size:14px;\">")
          .append("<p><strong>").append(esc(issue.getKey())).append("</strong>")
          .append(" — ").append(esc(issue.getSummary())).append("</p>")
          .append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;\">")
          .append("<tr style=\"background:#f5f5f5;\"><th>Поле</th><th>Было</th><th>Стало</th></tr>");

        for (DiffResult.FieldChange c : diff.getChanges()) {
            String from = c.fromValue() != null ? c.fromValue() : "";
            String to   = c.toValue()   != null ? c.toValue()   : "";

            if (from.length() + to.length() > DIFF_THRESHOLD) {
                appendDiffRow(sb, c.fieldName(), from, to);
            } else {
                appendSimpleRow(sb, c.fieldName(), from, to);
            }
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    private static void appendSimpleRow(StringBuilder sb, String field, String from, String to) {
        sb.append("<tr>")
          .append("<td>").append(esc(field)).append("</td>")
          .append("<td>").append(esc(from)).append("</td>")
          .append("<td>").append(esc(to)).append("</td>")
          .append("</tr>");
    }

    private static void appendDiffRow(StringBuilder sb, String field, String from, String to) {
        List<TextDiff.Line> lines = TextDiff.diff(from, to);

        sb.append("<tr>")
          .append("<td valign=\"top\">").append(esc(field)).append("</td>")
          .append("<td colspan=\"2\">")
          .append("<pre style=\"font-size:12px;background:#f8f8f8;padding:8px;")
          .append("white-space:pre-wrap;margin:0;\">");

        for (TextDiff.Line line : lines) {
            switch (line.marker()) {
                case '-' -> sb.append("<span style=\"color:#c00\">- ").append(esc(line.text())).append("</span>\n");
                case '+' -> sb.append("<span style=\"color:#060\">+ ").append(esc(line.text())).append("</span>\n");
                default  -> sb.append("  ").append(esc(line.text())).append("\n");
            }
        }

        sb.append("</pre></td></tr>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
