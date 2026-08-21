package ru.my.api;

import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.DelegationInfo;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.Optional;

/**
 * Сервис делегирования уведомлений.
 * Позволяет пользователю перенаправить свои уведомления коллеге
 * на период отпуска или больничного.
 */
public interface DelegationService {

    /**
     * Возвращает фактического получателя уведомления.
     * Если для {@code user} есть активная делегация — возвращает делегата.
     * В противном случае (делегации нет, истекла или делегат удалён из Jira) —
     * возвращает самого {@code user}.
     * <p>
     * Ограничение: делегирование однозвенное. Если делегат {@code B} сам
     * делегировал уведомления на {@code C}, уведомление всё равно придёт {@code B},
     * а не {@code C}. Транзитивное делегирование не поддерживается намеренно —
     * чтобы избежать цикличности и неочевидных цепочек перенаправлений.
     *
     * @param user исходный наблюдатель задачи
     * @return пользователь, которому нужно отправить уведомление
     */
    ApplicationUser getEffectiveRecipient(ApplicationUser user);

    /**
     * Устанавливает или обновляет делегацию (upsert).
     *
     * @param from        пользователь, который делегирует
     * @param to          получатель уведомлений на время делегации
     * @param activeUntil дата окончания делегации; {@code null} — бессрочная
     */
    void setDelegation(ApplicationUser from, ApplicationUser to, @Nullable Date activeUntil);

    /**
     * Снимает делегацию пользователя. Если делегации не было — ничего не делает.
     *
     * @param from пользователь, чья делегация снимается
     */
    void removeDelegation(ApplicationUser from);

    /**
     * Возвращает текущую делегацию пользователя (активную или истёкшую).
     *
     * @param from пользователь
     * @return делегация или {@link Optional#empty()} если делегации нет
     */
    Optional<DelegationInfo> getDelegation(ApplicationUser from);
}
