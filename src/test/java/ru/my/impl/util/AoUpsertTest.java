package ru.my.impl.util;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.sal.api.transaction.TransactionCallback;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import ru.my.ao.AdminSettingsEntity;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AoUpsertTest {
    private final ActiveObjects ao = mock(ActiveObjects.class);
    private final AdminSettingsEntity row = mock(AdminSettingsEntity.class);

    @Before
    public void transactionsExecuteTheirCallbacks() {
        when(ao.executeInTransaction(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction());
    }

    @Test
    public void retriesConcurrentCreateInNewTransaction() {
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0], new AdminSettingsEntity[]{row});
        when(ao.create(eq(AdminSettingsEntity.class), any(DBParam.class)))
                .thenThrow(new IllegalStateException("unique constraint"));

        AoUpsert.save(ao, AdminSettingsEntity.class, "SETTING_KEY", "key", r -> r.setSettingValue("new"));

        verify(ao, times(2)).executeInTransaction(any());
        verify(row).setSettingValue("new");
        verify(row).save();
    }

    @Test
    public void preservesOriginalCreateErrorWhenNoConcurrentRowExists() {
        RuntimeException original = new IllegalStateException("database unavailable");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0]);
        when(ao.create(eq(AdminSettingsEntity.class), any(DBParam.class))).thenThrow(original);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                AoUpsert.save(ao, AdminSettingsEntity.class, "SETTING_KEY", "key", r -> r.setSettingValue("new")));

        assertSame(original, thrown);
        verify(ao, times(2)).executeInTransaction(any());
        verifyNoInteractions(row);
    }

    @Test
    public void doesNotRetryUpdateFailure() {
        RuntimeException original = new IllegalStateException("save failed");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        doThrow(original).when(row).save();

        assertSame(original, assertThrows(RuntimeException.class, () ->
                AoUpsert.save(ao, AdminSettingsEntity.class, "SETTING_KEY", "key", r -> r.setSettingValue("new"))));
        verify(ao, times(1)).executeInTransaction(any());
    }
}
