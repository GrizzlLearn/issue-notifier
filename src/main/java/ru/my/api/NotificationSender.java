package ru.my.api;

import com.atlassian.jira.user.ApplicationUser;
import ru.my.model.NotificationChannel;

import java.util.Map;

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
     * не блокировал доставку по остальным. «Некуда доставить» (нет email,
     * не задан chat_id, пользователя нет в мессенджере) — тоже ошибка: иначе
     * вызывающий считает получателя уведомлённым, хотя не ушло ничего.
     *
     * @param recipient получатель
     * @param message   готовый текст сообщения (уже отформатированный под канал)
     */
    void send(ApplicationUser recipient, String message);

    /**
     * Проверочная отправка с админ-страницы: значения настроек канала берутся из
     * {@code settings} (несохранённая форма), а чего там нет — из сохранённых настроек.
     * Ничего не сохраняет. Бросает unchecked-исключение с текстом для администратора.
     *
     * @param recipient получатель — администратор, нажавший «Проверить»
     * @param message   текст проверочного сообщения
     * @param settings  значения полей канала с формы; пустые значения игнорируются
     */
    void sendTest(ApplicationUser recipient, String message, Map<String, String> settings);

    /**
     * Канал, который обслуживает этот отправщик.
     * Используется для построения маппинга в {@link ru.my.impl.NotificationServiceImpl}.
     */
    NotificationChannel channel();
}
