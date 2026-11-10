package ru.my.model;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class QueuedIssueEventTest {
    @Test
    public void roundTripPreservesFullCommentAndAllChangeFields() {
        String body = "Юникод 😀".repeat(100) + " [~tail]";
        QueuedIssueEvent original = new QueuedIssueEvent(42L, "author", 6L, true, 88L, body, true,
                new DiffResult(List.of(new DiffResult.FieldChange("Поле", null, "новое", "123", true))));
        QueuedIssueEvent restored = QueuedIssueEvent.decode(original.encode());
        assertEquals(original.issueId(), restored.issueId());
        assertEquals(original.authorKey(), restored.authorKey());
        assertEquals(original.typeId(), restored.typeId());
        assertTrue(restored.hasComment());
        assertEquals(original.commentId(), restored.commentId());
        assertEquals(body, restored.commentBody());
        assertTrue(restored.restrictedByLevel());
        DiffResult.FieldChange change = restored.diff().getChanges().get(0);
        assertEquals("Поле", change.fieldName());
        assertNull(change.fromValue());
        assertEquals("новое", change.toValue());
        assertEquals("123", change.toId());
        assertTrue(change.isCustom());
    }
}
