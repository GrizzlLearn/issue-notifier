package ru.my.model;

import java.time.Instant;

/**
 * Информация об активной делегации уведомлений.
 * Читается из {@link ru.my.ao.NotificationDelegationEntity}
 * через {@link ru.my.api.DelegationService}.
 */
public class DelegationInfo {

    private final String toUserKey;
    private final Instant activeUntil;

    /**
     * @param toUserKey   ключ пользователя-получателя уведомлений
     * @param activeUntil момент окончания делегации; {@code null} — бессрочная
     */
    public DelegationInfo(String toUserKey, Instant activeUntil) {
        this.toUserKey = toUserKey;
        this.activeUntil = activeUntil;
    }

    /** Ключ пользователя, которому перенаправляются уведомления. */
    public String getToUserKey() {
        return toUserKey;
    }

    /**
     * Момент окончания делегации. {@code null} означает бессрочную делегацию.
     * Для проверки актуальности используйте {@link #isActive()}.
     */
    public Instant getActiveUntil() {
        return activeUntil;
    }

    /**
     * {@code true} — делегация ещё действует.
     * Делегация считается истёкшей если {@code activeUntil} задан и уже в прошлом.
     */
    public boolean isActive() {
        return activeUntil == null || activeUntil.isAfter(Instant.now());
    }
}
