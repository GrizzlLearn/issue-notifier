package ru.my.impl.mattermost;

import com.atlassian.jira.issue.Issue;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import ru.my.api.MessageFormatter;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import ru.my.impl.util.ChangeSplitter;
import ru.my.impl.util.TextDiff;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

@Named
@ExportAsService(MessageFormatter.class)
public class MattermostMessageFormatter implements MessageFormatter {

    static final int DIFF_THRESHOLD = 300;

    private final ApplicationProperties applicationProperties;

    @Inject
    public MattermostMessageFormatter(@ComponentImport ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Override
    public String format(Issue issue, DiffResult diff) {
        StringBuilder sb = new StringBuilder();
        String issueUrl = applicationProperties.getBaseUrl() + "/browse/" + issue.getKey();
        sb.append("В задаче **[").append(mdEsc(issue.getKey())).append("](").append(issueUrl).append(")** — ")
          .append(mdEsc(issue.getSummary())).append(" произошли следующие изменения:\n\n");

        List<DiffResult.FieldChange> shortChanges = ChangeSplitter.shortChanges(diff.getChanges(), DIFF_THRESHOLD);
        List<DiffResult.FieldChange> longChanges = ChangeSplitter.longChanges(diff.getChanges(), DIFF_THRESHOLD);

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

    /** Экранирует спецсимволы Markdown внутри ячейки таблицы. */
    /**
     * Экранирует то, что ломает вёрстку Mattermost: {@code |} рвёт строку таблицы,
     * а квадратные скобки в заголовке задачи превращаются в ссылку-обрубок рядом
     * с настоящей ссылкой на задачу.
     */
    private static String mdEsc(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\n", " ")
                .replace("\r", "");
    }
}
