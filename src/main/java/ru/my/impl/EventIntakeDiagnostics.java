package ru.my.impl;

import org.codehaus.jackson.annotate.JsonProperty;
import javax.inject.Named;
import java.util.UUID;

/** Диагностика приёма на текущем экземпляре плагина; не зависит от доступности AO. */
@Named
public class EventIntakeDiagnostics {
    private final long startedAt = System.currentTimeMillis();
    private final String instanceId = UUID.randomUUID().toString();
    private long failures;
    private Long lastFailedAt;
    private String lastError;

    public synchronized void recordFailure(Exception failure) {
        if (failures < Long.MAX_VALUE) failures++;
        lastFailedAt = System.currentTimeMillis();
        // Сообщения исключений могут содержать тело задачи, SQL или секреты.
        lastError = "Ошибка приёма события (" + failure.getClass().getSimpleName() + ")";
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(instanceId, startedAt, failures, lastFailedAt, lastError);
    }

    public static final class Snapshot {
        @JsonProperty public final String instanceId;
        @JsonProperty public final long startedAt;
        @JsonProperty public final long failures;
        @JsonProperty public final Long lastFailedAt;
        @JsonProperty public final String lastError;

        private Snapshot(String instanceId, long startedAt, long failures, Long lastFailedAt, String lastError) {
            this.instanceId = instanceId;
            this.startedAt = startedAt;
            this.failures = failures;
            this.lastFailedAt = lastFailedAt;
            this.lastError = lastError;
        }
    }
}
