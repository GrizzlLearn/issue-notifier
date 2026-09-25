package ru.my.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WatchedFieldsTest {

    private static final DiffResult.FieldChange DESCRIPTION =
            new DiffResult.FieldChange("description", "было", "стало", null, false);
    private static final DiffResult.FieldChange STATUS =
            new DiffResult.FieldChange("status", "Открыта", "Закрыта", "6", false);
    private static final DiffResult.FieldChange CUSTOM =
            new DiffResult.FieldChange("Заказчик", null, "Иванов", null, true);

    private static final DiffResult ALL = new DiffResult(List.of(DESCRIPTION, STATUS, CUSTOM));

    @Test
    public void emptySettingKeepsEverything() {
        assertSame(ALL, WatchedFields.filter("", ALL));
        assertSame(ALL, WatchedFields.filter(null, ALL));
    }

    @Test
    public void keepsOnlyCheckedGroups() {
        DiffResult filtered = WatchedFields.filter("description,custom", ALL);

        assertEquals(List.of(DESCRIPTION, CUSTOM), filtered.getChanges());
    }

    /** Кастомное поле с именем системного всё равно попадает в группу «Кастомные». */
    @Test
    public void customFieldIsRecognizedByFieldTypeNotByName() {
        DiffResult.FieldChange customNamedLikeSystem =
                new DiffResult.FieldChange("description", null, "текст", null, true);
        DiffResult diff = new DiffResult(List.of(customNamedLikeSystem));

        assertTrue(WatchedFields.filter("description", diff).isEmpty());
        assertEquals(1, WatchedFields.filter("custom", diff).getChanges().size());
    }

    @Test
    public void otherGroupCoversRemainingSystemFields() {
        assertEquals(List.of(STATUS), WatchedFields.filter("other", ALL).getChanges());
    }

    @Test
    public void noneMeansNothingIsSent() {
        assertTrue(WatchedFields.filter(WatchedFields.NONE, ALL).isEmpty());
    }

    @Test
    public void unknownGroupIsIgnored() {
        assertTrue(WatchedFields.filter("нет-такой-группы", ALL).isEmpty());
    }

    @Test
    public void includesTreatsEmptySettingAsAllGroups() {
        assertTrue(WatchedFields.includes("", WatchedFields.CUSTOM));
        assertTrue(WatchedFields.includes("custom", WatchedFields.CUSTOM));
        assertTrue(!WatchedFields.includes("custom", WatchedFields.DESCRIPTION));
    }
}
