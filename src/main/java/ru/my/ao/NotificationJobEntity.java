package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

import java.util.Date;

/** Событие в очереди: удаляется только после успешной обработки; повтор возможен после сбоя. */
@Table("NOTIFICATION_JOB")
public interface NotificationJobEntity extends Entity {
    @NotNull
    @StringLength(StringLength.UNLIMITED)
    String getPayload();
    void setPayload(String payload);

    @NotNull
    @Indexed
    Date getAvailableAt();
    void setAvailableAt(Date availableAt);

    int getAttempts();
    void setAttempts(int attempts);
}
