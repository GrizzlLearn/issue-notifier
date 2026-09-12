package ru.my.api;

import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.DelegationInfo;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Сервис делегирования уведомлений.
 * Позволяет пользователю перенаправить свои уведомления одному или нескольким
 * коллегам на период отпуска или больничного.
 */
public interface DelegationService {

    /**
     * Возвращает фактических получателей уведомления.
     * Если для {@code user} есть активная делегация — возвращает делегатов.
     * В противном случае (делегации нет, истекла или все делегаты удалены из Jira) —
     * возвращает список из самого {@code user}.
     * <p>
     * Ограничение: делегирование однозвенное. Если делегат {@code B} сам
     * делегировал уведомления на {@code C}, уведомление всё равно придёт {@code B},
     * а не {@code C}. Транзитивное делегирование не поддерживается намеренно —
     * чтобы избежать цикличности и неочевидных цепочек перенаправлений.
     *
     * @param user исходный наблюдатель задачи
     * @return пользователи, которым нужно отправить уведомление
     */
    List<ApplicationUser> getEffectiveRecipients(ApplicationUser user);

    /**
     * Устанавливает или обновляет делегацию (upsert).
     *
     * @param from        пользователь, который делегирует
     * @param to          получатели уведомлений на время делегации; не должен быть пустым
     * @param activeUntil момент окончания делегации; {@code null} — бессрочная
     */
    void setDelegation(ApplicationUser from, List<ApplicationUser> to, @Nullable Instant activeUntil);

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
