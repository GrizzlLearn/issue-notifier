package ru.my.model;

/**
 * Тексты проверочной отправки со страницы настроек — свой на каждый канал.
 * <p>
 * Шаблоном администратора это не делается намеренно: проверка нужна до того, как
 * настроены шаблоны, и её текст должен быть узнаваем. Разметка у каналов разная,
 * поэтому один текст на всех не годится: Mattermost рендерит Markdown, Telegram
 * принимает HTML ({@code parse_mode=HTML}), почта — письмо целиком.
 */
public final class TestMessages {

    /** Тема проверочного письма — отличается от темы уведомлений об изменениях. */
    public static final String EMAIL_SUBJECT = "Issue Notifier: проверка канала Email";

    private static final String HEADING = "Issue Notifier: проверка канала";

    private static final String BODY =
            "Настройки верные — сообщение дошло. Уведомления о задачах будут приходить сюда же.";

    private static final String FOOTER = "Отправлено со страницы настроек плагина";

    private TestMessages() {
    }

    /**
     * Текст проверки для канала — единственный вход: разметку выбирает канал,
     * а не вызывающий код, иначе в Telegram однажды уедет Markdown.
     *
     * @param channel   канал доставки — от него зависит разметка
     * @param initiator кто нажал «Проверить»; пустое значение допустимо
     */
    public static String forChannel(NotificationChannel channel, String initiator) {
        return switch (channel) {
            case EMAIL -> emailHtml(initiator);
            case TELEGRAM -> telegramHtml(initiator);
            case MATTERMOST -> mattermostMarkdown(initiator);
        };
    }

    /**
     * Mattermost: Markdown, как у уведомлений
     * ({@link ru.my.impl.mattermost.MattermostMessageFormatter}). Имя администратора
     * не экранируется — markdown-спецсимволы в свободном тексте безвредны, а
     * обратные слэши получатель бы увидел.
     */
    private static String mattermostMarkdown(String initiator) {
        return "**" + HEADING + " Mattermost**\n"
                + BODY + "\n"
                + "_" + FOOTER + by(initiator, false) + "._";
    }

    /**
     * Telegram: HTML-подмножество, которое принимает Bot API
     * (см. {@link ru.my.impl.telegram.TelegramMessageFormatter}). Имя администратора
     * экранируется — иначе угловая скобка в имени сломает разбор сообщения.
     */
    private static String telegramHtml(String initiator) {
        return "<b>" + HEADING + " Telegram</b>\n"
                + BODY + "\n"
                + "<i>" + FOOTER + by(initiator, true) + ".</i>";
    }

    /**
     * Тело проверочного письма — тем же устройством, что и уведомления
     * ({@link ru.my.impl.email.EmailMessageFormatter}): {@code <html><body>} со стилями
     * прямо в атрибутах.
     * <p>
     * {@code <head>} нет намеренно: кодировку задаёт MIME-заголовок письма, который
     * ставит почтовая очередь Jira, а {@code <head>} и {@code <style>} почтовые
     * клиенты всё равно вырезают — поэтому и стили инлайновые.
     */
    private static String emailHtml(String initiator) {
        return "<html><body style=\"font-family:Arial,sans-serif;font-size:14px;color:#172b4d;\">"
                + "<p style=\"margin:0 0 12px\"><b>Это проверочное письмо плагина Issue Notifier.</b></p>"
                + "<p style=\"margin:0 0 12px\">Оно подтверждает, что почтовый канал настроен: "
                + "Jira приняла письмо в очередь отправки и адрес получателя верный. "
                + "Уведомления об изменениях задач будут приходить на этот же адрес.</p>"
                + "<p style=\"margin:0 0 12px;color:#5e6c84\">" + FOOTER + by(initiator, true)
                + ". Отвечать на это письмо не нужно.</p>"
                + "</body></html>";
    }

    /** «администратором Имя» либо пустая строка, если имя неизвестно. */
    private static String by(String initiator, boolean escape) {
        if (initiator == null || initiator.isBlank()) {
            return "";
        }
        return " администратором " + (escape ? escape(initiator) : initiator);
    }

    /** Имя приходит из Jira, но в разметке всё равно экранируется. */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
