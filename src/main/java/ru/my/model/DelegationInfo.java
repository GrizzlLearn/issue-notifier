package ru.my.model;

import java.time.Instant;
import java.util.List;

/**
 * Информация об активной делегации уведомлений.
 * Читается из {@link ru.my.ao.NotificationDelegationEntity}
 * через {@link ru.my.api.DelegationService}.
 */
public class DelegationInfo {

    private final List<String> toUserKeys;
    private final Instant activeUntil;

    /**
     * @param toUserKeys  ключи пользователей-получателей уведомлений
     * @param activeUntil момент окончания делегации; {@code null} — бессрочная
     */
    public DelegationInfo(List<String> toUserKeys, Instant activeUntil) {
        this.toUserKeys = List.copyOf(toUserKeys);
        this.activeUntil = activeUntil;
    }

    /** Ключи пользователей, которым перенаправляются уведомления. */
    public List<String> getToUserKeys() {
        return toUserKeys;
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
