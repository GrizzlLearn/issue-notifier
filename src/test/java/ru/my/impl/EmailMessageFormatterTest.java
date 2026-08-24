package ru.my.impl;

import com.atlassian.jira.issue.Issue;
import org.junit.Test;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EmailMessageFormatterTest {

    private final EmailMessageFormatter formatter = new EmailMessageFormatter();

    @Test
    public void channelIsEmail() {
        assertEquals(NotificationChannel.EMAIL, formatter.channel());
    }

    @Test
    public void formatsTableWithChanges() {
        Issue issue = mockIssue("PROJ-1", "Сделать что-нибудь");
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Status", "Open", "In Progress")));

        String html = formatter.format(issue, diff);

        assertTrue(html.contains("PROJ-1"));
        assertTrue(html.contains("Сделать что-нибудь"));
        assertTrue(html.contains("Status"));
        assertTrue(html.contains("Open"));
        assertTrue(html.contains("In Progress"));
        assertTrue(html.contains("<table"));
    }

    @Test
    public void handlesNullFieldValues() {
        Issue issue = mockIssue("PROJ-2", "Задача");
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Assignee", null, "alice")));

        String html = formatter.format(issue, diff);

        assertTrue(html.contains("Assignee"));
        assertTrue(html.contains("alice"));
        assertFalse(html.contains(">null<"));
    }

    @Test
    public void escapesHtmlSpecialChars() {
        Issue issue = mockIssue("PROJ-3", "<b>Bold &amp; Title</b>");
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("<script>", "a&b", "c>d")));

        String html = formatter.format(issue, diff);

        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("a&amp;b"));
        assertTrue(html.contains("c&gt;d"));
    }

    @Test
    public void handlesMultipleChanges() {
        Issue issue = mockIssue("PROJ-4", "Задача");
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Status", "Open", "Done"),
                new DiffResult.FieldChange("Priority", "High", "Low")));

        String html = formatter.format(issue, diff);

        assertTrue(html.contains("Status"));
        assertTrue(html.contains("Priority"));
        assertTrue(html.contains("Done"));
        assertTrue(html.contains("Low"));
    }

    @Test
    public void longValueTriggersInlineDiff() {
        Issue issue = mockIssue("PROJ-5", "Задача");
        String longFrom = "строка один\nстрока два\nстрока три\n".repeat(10);
        String longTo   = "строка один\nстрока ДВА\nстрока три\n".repeat(10);
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Description", longFrom, longTo)));

        String html = formatter.format(issue, diff);

        assertTrue(html.contains("<pre"));
        assertTrue(html.contains("colspan=\"2\""));
        // изменённые строки помечены цветом
        assertTrue(html.contains("color:#c00")); // удалено
        assertTrue(html.contains("color:#060")); // добавлено
        // неизменённые строки присутствуют как контекст
        assertTrue(html.contains("строка один"));
    }

    @Test
    public void shortValueUsesSimpleColumns() {
        Issue issue = mockIssue("PROJ-6", "Задача");
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Status", "Open", "Done")));

        String html = formatter.format(issue, diff);

        assertFalse(html.contains("<pre"));
        assertFalse(html.contains("colspan"));
    }

    private static Issue mockIssue(String key, String summary) {
        Issue issue = mock(Issue.class);
        when(issue.getKey()).thenReturn(key);
        when(issue.getSummary()).thenReturn(summary);
        return issue;
    }
}
