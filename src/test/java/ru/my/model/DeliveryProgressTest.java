package ru.my.model;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DeliveryProgressTest {
    @Test
    public void durableSnapshotRestoresDistinctTuples() {
        AtomicReference<String> stored = new AtomicReference<>();
        DeliveryProgress progress = new DeliveryProgress(null, stored::set);
        progress.record(NotificationAction.MENTION, NotificationChannel.TELEGRAM, "a\",:[b");
        progress.record(null, NotificationChannel.EMAIL, "a\",:[b");
        DeliveryProgress restored = new DeliveryProgress(stored.get(), snapshot -> fail("read only"));
        assertTrue(restored.delivered(NotificationAction.MENTION, NotificationChannel.TELEGRAM, "a\",:[b"));
        assertTrue(restored.delivered(null, NotificationChannel.EMAIL, "a\",:[b"));
        assertFalse(restored.delivered(NotificationAction.ASSIGNED, NotificationChannel.TELEGRAM, "a\",:[b"));
        assertFalse(restored.delivered(null, NotificationChannel.TELEGRAM, "a\",:[b"));
    }

    @Test
    public void failedCheckpointDoesNotAdvanceLocalOrDurableState() {
        AtomicReference<String> stored = new AtomicReference<>();
        DeliveryProgress first = new DeliveryProgress(null, stored::set);
        first.record(null, NotificationChannel.EMAIL, "a");
        String old = stored.get();
        DeliveryProgress failed = new DeliveryProgress(old, snapshot -> { throw new IllegalStateException("DB down"); });
        assertThrows(IllegalStateException.class, () -> failed.record(null, NotificationChannel.EMAIL, "b"));
        assertFalse(failed.delivered(null, NotificationChannel.EMAIL, "b"));
        assertTrue(failed.delivered(null, NotificationChannel.EMAIL, "a"));
        assertEquals(old, stored.get());
    }

    @Test
    public void duplicateReceiptDoesNotWriteAgain() {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        DeliveryProgress progress = new DeliveryProgress(" ", snapshot -> writes.incrementAndGet());
        progress.record(null, NotificationChannel.EMAIL, "a");
        progress.record(null, NotificationChannel.EMAIL, "a");
        assertEquals(1, writes.get());
    }

    @Test
    public void corruptLedgerIsNeverTreatedAsEmpty() {
        for (String raw : new String[]{"{", "null", "{}", "[1]", "[\"bad\"]", "[\"[]\"]"}) {
            assertThrows(IllegalArgumentException.class, () -> new DeliveryProgress(raw, snapshot -> fail()));
        }
    }
}
