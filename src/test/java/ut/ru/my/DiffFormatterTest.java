package ut.ru.my;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.ofbiz.core.entity.GenericValue;
import ru.my.impl.DiffFormatter;
import ru.my.model.DiffResult;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DiffFormatterTest {

    @Test
    public void returnsEmptyDiffWhenChangeLogIsNull() {
        DiffResult result = DiffFormatter.parse(null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void parsesSingleFieldChange() {
        GenericValue item = mockItem("Assignee", "Иван", "Пётр");
        DiffResult result = DiffFormatter.parseItems(List.of(item));

        assertEquals(1, result.getChanges().size());
        DiffResult.FieldChange change = result.getChanges().get(0);
        assertEquals("Assignee", change.fieldName());
        assertEquals("Иван", change.fromValue());
        assertEquals("Пётр", change.toValue());
    }

    @Test
    public void parsesMultipleFieldChanges() {
        GenericValue item1 = mockItem("Status", "Open", "In Progress");
        GenericValue item2 = mockItem("Priority", "Medium", "High");
        DiffResult result = DiffFormatter.parseItems(List.of(item1, item2));

        assertEquals(2, result.getChanges().size());
    }

    /**
     * Поле было очищено — newstring = null (Jira ставит null, не пустую строку).
     */
    @Test
    public void handlesFieldClearedToNull() {
        GenericValue item = mockItem("Fix Version", "1.0", null);
        DiffResult result = DiffFormatter.parseItems(List.of(item));

        assertEquals(1, result.getChanges().size());
        assertEquals("1.0", result.getChanges().get(0).fromValue());
        assertNull(result.getChanges().get(0).toValue());
    }

    /**
     * Поле заполнено впервые — oldstring = null.
     */
    @Test
    public void handlesFieldSetForFirstTime() {
        GenericValue item = mockItem("Due Date", null, "2026-12-31");
        DiffResult result = DiffFormatter.parseItems(List.of(item));

        assertEquals(1, result.getChanges().size());
        assertNull(result.getChanges().get(0).fromValue());
        assertEquals("2026-12-31", result.getChanges().get(0).toValue());
    }

    /**
     * Запись с пустым полем field (защита от мусорных данных Jira).
     */
    @Test
    public void skipsItemsWithBlankFieldName() {
        GenericValue blank = mockItem("", "old", "new");
        GenericValue valid = mockItem("Status", "Open", "Closed");
        DiffResult result = DiffFormatter.parseItems(List.of(blank, valid));

        assertEquals(1, result.getChanges().size());
        assertEquals("Status", result.getChanges().get(0).fieldName());
    }

    // ---- вспомогательные методы ----------------------------------------

    private GenericValue mockItem(String field, String oldString, String newString) {
        GenericValue item = mock(GenericValue.class);
        when(item.getString("field")).thenReturn(field);
        when(item.getString("oldstring")).thenReturn(oldString);
        when(item.getString("newstring")).thenReturn(newString);
        return item;
    }

    private static void assertNull(Object value) {
        org.junit.Assert.assertNull(value);
    }
}
