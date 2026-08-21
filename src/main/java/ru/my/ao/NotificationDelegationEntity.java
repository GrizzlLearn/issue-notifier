package ru.my.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Table;

import java.util.Date;

/**
 * AO-сущность: делегирование уведомлений на период отпуска или больничного.
 * <p>
 * Одна строка на пользователя-делегирующего. Уникальность по {@code FROM_USER_KEY}
 * обеспечивается upsert-логикой в {@link ru.my.impl.DelegationServiceImpl}.
 */
@Table("NOTIFICATION_DELEGATION")
public interface NotificationDelegationEntity extends Entity {

    /** Ключ пользователя, который делегирует уведомления. */
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
