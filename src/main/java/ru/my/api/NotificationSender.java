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
     * Проверочная отправка на адрес, не привязанный к пользователю Jira: админ
     * проверяет доставку на конкретный ящик, не имея такого пользователя.
     * <p>
     * По умолчанию не поддерживается — в Telegram адресата определяет chat_id из
     * настроек пользователя, произвольного адреса там нет.
     *
     * @param email    адрес получателя; для Mattermost по нему ищется пользователь
     * @param message  текст проверочного сообщения
     * @param settings значения полей канала с формы
     */
    default void sendTestTo(String email, String message, Map<String, String> settings) {
        throw new UnsupportedOperationException(
                "Канал " + channel() + " умеет проверку только на пользователя Jira");
    }

    /**
     * Канал, который обслуживает этот отправщик.
     * Используется для построения маппинга в {@link ru.my.impl.NotificationServiceImpl}.
     */
    NotificationChannel channel();
}
