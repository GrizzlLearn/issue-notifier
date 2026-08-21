package ru.my.api;

import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.NotificationChannel;

/**
 * Транспортный уровень доставки уведомлений: отвечает только за отправку
 * уже сформированного сообщения по конкретному каналу.
 * Форматирование текста — ответственность {@link MessageFormatter}.
 */
public interface NotificationSender {

    /**
     * Отправляет сообщение пользователю.
     * В случае ошибки бросает unchecked-исключение — его перехватывает
     * {@link ru.my.impl.NotificationServiceImpl}, чтобы сбой одного канала
     * не блокировал доставку по остальным.
     *
     * @param recipient получатель
     * @param message   готовый текст сообщения (уже отформатированный под канал)
     */
    void send(ApplicationUser recipient, String message);

    /**
     * Канал, который обслуживает этот отправщик.
     * Используется для построения маппинга в {@link ru.my.impl.NotificationServiceImpl}.
     */
    NotificationChannel channel();
}
