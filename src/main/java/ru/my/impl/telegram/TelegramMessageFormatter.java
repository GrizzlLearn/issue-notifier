package ru.my.impl.telegram;

import com.atlassian.jira.issue.Issue;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.my.api.MessageFormatter;
import ru.my.impl.util.TextDiff;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import javax.inject.Named;
import java.util.List;

@Named
@ExportAsService(MessageFormatter.class)
public class TelegramMessageFormatter implements MessageFormatter {

    static final int DIFF_THRESHOLD = 300;

    @Override
    public String format(Issue issue, DiffResult diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(htmlEsc(issue.getKey())).append("</b> — ")
          .append(htmlEsc(issue.getSummary())).append("\n\n");

        List<DiffResult.FieldChange> shortChanges = diff.getChanges().stream()
                .filter(c -> len(c.fromValue()) + len(c.toValue()) <= DIFF_THRESHOLD)
                .toList();
        List<DiffResult.FieldChange> longChanges = diff.getChanges().stream()
                .filter(c -> len(c.fromValue()) + len(c.toValue()) > DIFF_THRESHOLD)
                .toList();

        for (DiffResult.FieldChange c : shortChanges) {
            sb.append("<b>").append(htmlEsc(c.fieldName())).append(":</b> ");
            if (c.fromValue() != null) {
                sb.append("<s>").append(htmlEsc(c.fromValue())).append("</s> → ");
            }
            sb.append(c.toValue() != null ? htmlEsc(c.toValue()) : "—").append("\n");
        }

        for (DiffResult.FieldChange c : longChanges) {
            sb.append("\n<b>").append(htmlEsc(c.fieldName())).append(":</b>\n<pre>");
            for (TextDiff.Line line : TextDiff.diff(c.fromValue(), c.toValue())) {
                sb.append(htmlEsc(line.marker() + " " + line.text())).append("\n");
            }
            sb.append("</pre>\n");
        }

        return sb.toString();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    static String htmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
