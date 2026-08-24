package ru.my.impl.mattermost;

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
public class MattermostMessageFormatter implements MessageFormatter {

    static final int DIFF_THRESHOLD = 300;

    @Override
    public String format(Issue issue, DiffResult diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(issue.getKey()).append("** — ").append(issue.getSummary()).append("\n\n");

        List<DiffResult.FieldChange> shortChanges = diff.getChanges().stream()
                .filter(c -> len(c.fromValue()) + len(c.toValue()) <= DIFF_THRESHOLD)
                .toList();
        List<DiffResult.FieldChange> longChanges = diff.getChanges().stream()
                .filter(c -> len(c.fromValue()) + len(c.toValue()) > DIFF_THRESHOLD)
                .toList();

        if (!shortChanges.isEmpty()) {
            sb.append("| Поле | Было | Стало |\n|------|------|-------|\n");
            for (DiffResult.FieldChange c : shortChanges) {
                sb.append("| ").append(mdEsc(c.fieldName()))
                  .append(" | ").append(mdEsc(c.fromValue()))
                  .append(" | ").append(mdEsc(c.toValue()))
                  .append(" |\n");
            }
        }

        for (DiffResult.FieldChange c : longChanges) {
            sb.append("\n**").append(c.fieldName()).append(":**\n```diff\n");
            for (TextDiff.Line line : TextDiff.diff(c.fromValue(), c.toValue())) {
                sb.append(line.marker()).append(' ').append(line.text()).append('\n');
            }
            sb.append("```\n");
        }

        return sb.toString();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.MATTERMOST;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** Экранирует спецсимволы Markdown внутри ячейки таблицы. */
    private static String mdEsc(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }
}
