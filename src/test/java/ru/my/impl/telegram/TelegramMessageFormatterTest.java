package ru.my.impl.telegram;

import com.atlassian.jira.issue.Issue;
import org.junit.Test;
import ru.my.model.DiffResult;
import ru.my.model.NotificationChannel;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TelegramMessageFormatterTest {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    public void channelIsTelegram() {
        assertEquals(NotificationChannel.TELEGRAM, formatter.channel());
    }

    @Test
    public void headerContainsKeyAndSummary() {
        String result = formatter.format(mockIssue("Задача"), singleChange("Status", "Open", "Done"));

        assertTrue(result.contains("<b>PROJ-1</b>"));
        assertTrue(result.contains("Задача"));
    }

    @Test
    public void shortChangeShowsStrikethroughAndNewValue() {
        String result = formatter.format(mockIssue("X"), singleChange("Status", "Open", "Done"));

        assertTrue(result.contains("<s>Open</s>"));
        assertTrue(result.contains("Done"));
        assertFalse(result.contains("<pre>"));
    }

    @Test
    public void nullFromValueRendersWithoutStrikethrough() {
        String result = formatter.format(mockIssue("X"), singleChange("Assignee", null, "Alice"));

        assertFalse(result.contains("<s>"));
        assertTrue(result.contains("Alice"));
        assertFalse(result.contains("null"));
    }

    @Test
    public void nullToValueRendersEmDash() {
        String result = formatter.format(mockIssue("X"), singleChange("Assignee", "Bob", null));

        assertTrue(result.contains("<s>Bob</s>"));
        assertTrue(result.contains("→ —"));
    }

    @Test
    public void longChangeRendersAsPreBlock() {
        String longFrom = "строка один\nстрока два\n".repeat(12);
        String longTo   = "строка один\nстрока ДВА\n".repeat(12);

        String result = formatter.format(mockIssue("X"), singleChange("Description", longFrom, longTo));

        assertTrue(result.contains("<pre>"));
        assertTrue(result.contains("- строка два"));
        assertTrue(result.contains("+ строка ДВА"));
    }

    @Test
    public void htmlSpecialCharsAreEscaped() {
        String result = formatter.format(mockIssue("X"), singleChange("Field", "<b>old</b>", "a & b > c"));

        assertTrue(result.contains("&lt;b&gt;old&lt;/b&gt;"));
        assertTrue(result.contains("a &amp; b &gt; c"));
    }

    @Test
    public void htmlEscHandlesNull() {
        assertEquals("", TelegramMessageFormatter.htmlEsc(null));
    }

    @Test
    public void mixedShortAndLongChanges() {
        String longFrom = "x\n".repeat(80);
        String longTo   = "y\n".repeat(80);
        DiffResult diff = new DiffResult(List.of(
                new DiffResult.FieldChange("Status", "Open", "Done"),
                new DiffResult.FieldChange("Description", longFrom, longTo)));

        String result = formatter.format(mockIssue("X"), diff);

        assertTrue(result.contains("<s>Open</s>"));
        assertTrue(result.contains("<pre>"));
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
