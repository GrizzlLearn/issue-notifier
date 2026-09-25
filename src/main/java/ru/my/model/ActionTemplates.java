package ru.my.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ключи настроек и подстановка значений в шаблоны уведомлений о действиях
 * ({@link NotificationAction}).
 * <p>
 * Движка шаблонов нет намеренно: шаблон редактирует администратор, а Velocity
 * или Freemarker в редактируемом тексте — это выполнение кода. Здесь только
 * замена плейсхолдеров из белого списка действия.
 */
public final class ActionTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private static final String NO_TEXT_SUFFIX = ".notext";

    /**
     * Ключ настройки «не отправлять текст комментария» — общий для всех действий
     * с плейсхолдером {@code {comment}}. Хранится инвертированным: отсутствие
     * записи означает «текст отправляем», как плагин вёл себя раньше.
     */
    public static final String HIDE_COMMENT_TEXT_KEY = "comment.hideText";

    /**
     * Ключ настройки «не уведомлять наблюдателей об изменениях задач». Хранится
     * инвертированным по той же причине, что и текст комментария: отсутствие
     * записи означает «уведомляем», как плагин вёл себя до появления галки.
     * Получателей действий настройка не касается — их задаёт список получателей действия.
     */
    public static final String WATCHERS_DISABLED_KEY = "watchers.disabled";

    private ActionTemplates() {
    }

    /** Ключ флага «уведомлять об этом действии», например {@code "action.mention.enabled"}. */
    public static String enabledKey(NotificationAction action) {
        return "action." + action.key() + ".enabled";
    }

    /** Ключ списка получателей, например {@code "action.closed.recipients"}. */
    public static String recipientsKey(NotificationAction action) {
        return "action." + action.key() + ".recipients";
    }

    /** Ключ области действия, например {@code "action.mention.scope"}. */
    public static String scopeKey(NotificationAction action) {
        return "action." + action.key() + ".scope";
    }

    /**
     * Возвращает действие, которому принадлежит ключ области.
     *
     * @return действие или {@code null}, если ключ не является ключом области
     */
    public static NotificationAction actionOfScopeKey(String key) {
        for (NotificationAction action : NotificationAction.values()) {
            if (scopeKey(action).equals(key)) {
                return action;
            }
        }
        return null;
    }

    /** Ключ шаблона канала, например {@code "action.mention.template.mattermost"}. */
    public static String templateKey(NotificationAction action, NotificationChannel channel) {
        return "action." + action.key() + ".template." + channel.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Ключ шаблона для случая, когда текст комментария отправлять нельзя,
     * например {@code "action.commentAdded.template.mattermost.notext"}.
     * <p>
     * Отдельный шаблон вместо заглушки вместо {@code {comment}}: текст «Комментарий:»
     * перед пустым местом выглядит сбоем, а формулировка без текста обычно другая.
     */
    public static String templateKeyNoText(NotificationAction action, NotificationChannel channel) {
        return templateKey(action, channel) + NO_TEXT_SUFFIX;
    }

    /**
     * Возвращает действие, которому принадлежит ключ шаблона — обычного или
     * без текста комментария.
     *
     * @return действие или {@code null}, если ключ не является ключом шаблона
     */
    public static NotificationAction actionOfTemplateKey(String key) {
        String plain = key.endsWith(NO_TEXT_SUFFIX)
                ? key.substring(0, key.length() - NO_TEXT_SUFFIX.length())
                : key;
        for (NotificationAction action : NotificationAction.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                if (templateKey(action, channel).equals(plain)) {
                    return action;
                }
            }
        }
        return null;
    }

    /**
     * Подставляет значения в шаблон.
     * <p>
     * Экранируются подставляемые значения, а не сам шаблон: разметку в шаблоне
     * администратор пишет осознанно, а summary или текст комментария могут
     * содержать символы, ломающие разметку канала.
     *
     * @param template шаблон с плейсхолдерами вида {@code {issueKey}}
     * @param values   значения плейсхолдеров (без фигурных скобок)
     * @param channel  канал — определяет правила экранирования
     * @return готовый текст; неизвестные плейсхолдеры остаются в тексте как есть
     */
    public static String render(String template, Map<String, String> values, NotificationChannel channel) {
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", escape(e.getValue(), channel));
        }
        return result;
    }

    /**
     * Проверяет шаблон на плейсхолдеры, которых нет у действия — иначе опечатка
     * вроде {@code {issuekey}} молча уедет в сообщение в сыром виде.
     *
     * @return список неизвестных плейсхолдеров без скобок; пустой — шаблон корректен
     */
    public static List<String> unknownPlaceholders(String template, NotificationAction action) {
        List<String> unknown = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!action.placeholders().contains(name) && !unknown.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /**
     * Telegram принимает HTML-разметку, поэтому спецсимволы в значениях экранируются.
     * Для Mattermost экранирования нет: markdown-спецсимволы в свободном тексте
     * безвредны, а обратные слэши были бы видны получателю.
     */
    private static String escape(String value, NotificationChannel channel) {
        if (value == null) {
            return "";
        }
        if (channel == NotificationChannel.TELEGRAM) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        return value;
    }
}
