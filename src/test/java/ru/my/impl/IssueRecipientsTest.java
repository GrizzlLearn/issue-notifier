package ru.my.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.MockCustomField;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IssueRecipientsTest {

    private final ApplicationUser reporter = new MockApplicationUser("reporter");
    private final ApplicationUser creator  = new MockApplicationUser("creator");
    private final ApplicationUser watcher  = new MockApplicationUser("watcher");
    private final ApplicationUser approver = new MockApplicationUser("approver");
    private final ApplicationUser deputy   = new MockApplicationUser("deputy");

    private Issue issue;
    private WatcherManager watcherManager;
    private CustomFieldManager customFieldManager;
    private CustomField singleField;
    private CustomField multiField;

    @Before
    public void setUp() {
        issue = mock(Issue.class);
        when(issue.getReporter()).thenReturn(reporter);
        when(issue.getCreator()).thenReturn(creator);
        when(issue.getAssignee()).thenReturn(null);

        watcherManager = mock(WatcherManager.class);
        when(watcherManager.getWatchers(any(), any())).thenReturn(List.of(watcher));

        singleField = new MockCustomField("customfield_10100", "Согласующий", null);
        multiField = new MockCustomField("customfield_10200", "Ответственные", null);
        customFieldManager = mock(CustomFieldManager.class);
        when(customFieldManager.getCustomFieldObject("customfield_10100")).thenReturn(singleField);
        when(customFieldManager.getCustomFieldObject("customfield_10200")).thenReturn(multiField);
        when(issue.getCustomFieldValue(singleField)).thenReturn(approver);
        when(issue.getCustomFieldValue(multiField)).thenReturn(List.of(approver, deputy));
    }

    private List<ApplicationUser> resolve(String raw) {
        return IssueRecipients.resolve(raw, issue, watcherManager, customFieldManager);
    }

    @Test
    public void emptySettingMeansWatchers() {
        assertEquals(List.of(watcher), resolve(""));
        assertEquals(List.of(watcher), resolve(null));
    }

    /** Незаполненное поле (здесь — исполнитель) получателя не даёт, остальные источники работают. */
    /** Снятые в админке галки — это «никому», а не «по умолчанию наблюдателям». */
    @Test
    public void explicitNoneMeansNobody() {
        assertTrue(resolve("none").isEmpty());
    }

    @Test
    public void resolvesBuiltInFieldsSkippingEmptyOnes() {
        assertEquals(List.of(reporter, creator), resolve("reporter, assignee ,creator"));
    }

    @Test
    public void resolvesSingleAndMultiUserPickerFields() {
        assertEquals(List.of(approver), resolve("customfield_10100"));
        assertEquals(List.of(approver, deputy), resolve("customfield_10200"));
    }

    /** Поля, которого нет в схеме экрана задачи, в настройке достаточно игнорировать. */
    @Test
    public void unknownOrEmptyCustomFieldGivesNoRecipient() {
        when(issue.getCustomFieldValue(singleField)).thenReturn(null);

        assertTrue(resolve("customfield_10100").isEmpty());
        assertTrue(resolve("customfield_99999").isEmpty());
    }

    /** Один человек в нескольких полях — одно уведомление. */
    @Test
    public void deduplicatesRecipients() {
        assertEquals(List.of(approver, deputy), resolve("customfield_10100,customfield_10200"));
    }
}
