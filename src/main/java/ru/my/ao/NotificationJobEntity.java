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

    /** Безопасное описание последнего сбоя, без исходных сообщений и содержимого задач. */
    @StringLength(StringLength.UNLIMITED)
    String getLastError();
    void setLastError(String lastError);

    /** Время последнего сбоя; null у новых и старых записей без диагностики. */
    Date getLastFailedAt();
    void setLastFailedAt(Date lastFailedAt);

    /** JSON-журнал подтвержденных доставок; null у события без чекпойнтов. */
    @StringLength(StringLength.UNLIMITED)
    String getDeliveredRaw();
    void setDeliveredRaw(String deliveredRaw);

    /** null означает обычную очередь, включая записи до обновления. */
    Date getQuarantinedAt();
    void setQuarantinedAt(Date value);

    int getAttempts();
    void setAttempts(int attempts);
}
