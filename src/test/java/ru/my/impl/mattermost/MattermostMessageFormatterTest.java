package ru.my.impl.mattermost;

import com.atlassian.jira.issue.Issue;
import org.junit.Test;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MattermostMessageFormatterTest {

    private final MattermostMessageFormatter formatter = new MattermostMessageFormatter();

    @Test
    public void channelIsMattermost() {
        assertEquals(NotificationChannel.MATTERMOST, formatter.channel());
    }

    @Test
    public void headerContainsKeyAndSummary() {
        String result = formatter.format(mockIssue("Задача"), singleChange("Status", "Open", "Done"));

        assertTrue(result.contains("**PROJ-1**"));
        assertTrue(result.contains("Задача"));
    }

    @Test
    public void shortChangesRenderAsTable() {
        String result = formatter.format(mockIssue("X"), singleChange("Status", "Open", "Done"));

        assertTrue(result.contains("| Поле | Было | Стало |"));
        assertTrue(result.contains("Status"));
        assertTrue(result.contains("Open"));
        assertTrue(result.contains("Done"));
        assertFalse(result.contains("```diff"));
    }

    @Test
    public void nullValuesRenderAsEmptyCells() {
        String result = formatter.format(mockIssue("X"), singleChange("Assignee", null, "alice"));

        assertTrue(result.contains("Assignee"));
        assertTrue(result.contains("alice"));
        assertFalse(result.contains("null"));
    }

    @Test
    public void pipeInValueIsEscaped() {
        String result = formatter.format(mockIssue("X"), singleChange("Field", "a|b", "c"));

        assertTrue(result.contains("a\\|b"));
    }

    @Test
    public void longChangesRenderAsDiffBlock() {
        String longFrom = "строка один\nстрока два\n".repeat(12);
        String longTo   = "строка один\nстрока ДВА\n".repeat(12);
        String result = formatter.format(mockIssue("X"), singleChange("Description", longFrom, longTo));

        assertTrue(result.contains("```diff"));
        assertTrue(result.contains("- строка два"));
        assertTrue(result.contains("+ строка ДВА"));
        assertTrue(result.contains("  строка один")); // контекст
    }

    @Test
    public void mixedShortAndLongChanges() {
        String longFrom = "x\n".repeat(80);  // 160 символов
        String longTo   = "y\n".repeat(80);  // 160 символов, суммарно > DIFF_THRESHOLD
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Status", "Open", "Done"),
                new DiffResult.FieldChange("Description", longFrom, longTo)));

        String result = formatter.format(mockIssue("X"), diff);

        assertTrue(result.contains("| Поле | Было | Стало |")); // таблица для Status
        assertTrue(result.contains("```diff"));                 // блок для Description
    }

    private static DiffResult singleChange(String field, String from, String to) {
        return new DiffResult(List.of(new DiffResult.FieldChange(field, from, to)));
    }

    private static Issue mockIssue(String summary) {
        Issue issue = mock(Issue.class);
        when(issue.getKey()).thenReturn("PROJ-1");
        when(issue.getSummary()).thenReturn(summary);
        return issue;
    }
}
