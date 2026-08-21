package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

import java.util.Date;

/**
 * AO-сущность: делегирование уведомлений на период отпуска или больничного.
 * <p>
 * Одна строка на пользователя-делегирующего. {@code FROM_USER_KEY} имеет уникальный
 * индекс на уровне БД и NOT NULL-ограничение.
 */
@Table("NOTIFICATION_DELEGATION")
public interface NotificationDelegationEntity extends Entity {

    /** Ключ пользователя, который делегирует уведомления. */
    @Unique
    @NotNull
    String getFromUserKey();
    void setFromUserKey(String fromUserKey);

    /** Ключ пользователя, которому перенаправляются уведомления. */
    String getToUserKey();
    void setToUserKey(String toUserKey);

    /**
     * Дата окончания делегации. {@code null} — делегация бессрочная.
     * Если дата в прошлом — делегация считается снятой.
     */
    Date getActiveUntil();
    void setActiveUntil(Date activeUntil);
}
