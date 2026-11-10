package ru.my.impl;

import org.junit.Test;
import static org.junit.Assert.*;

public class EventIntakeDiagnosticsTest {
    @Test
    public void countsFailuresWithoutExceptionMessagesAndResetsWithNewInstance() {
        EventIntakeDiagnostics diagnostics = new EventIntakeDiagnostics();
        EventIntakeDiagnostics.Snapshot initial = diagnostics.snapshot();
        assertEquals(0, initial.failures);
        assertNull(initial.lastFailedAt);
        diagnostics.recordFailure(new IllegalStateException("secret body token"));
        diagnostics.recordFailure(new IllegalArgumentException("secret"));
        var result = diagnostics.snapshot();
        assertEquals(2, result.failures);
        assertTrue(result.lastFailedAt >= initial.startedAt);
        assertEquals("Ошибка приёма события (IllegalArgumentException)", result.lastError);
        assertEquals(0, initial.failures);
        var restarted = new EventIntakeDiagnostics().snapshot();
        assertEquals(0, restarted.failures);
        assertNotEquals(result.instanceId, restarted.instanceId);
    }
}
