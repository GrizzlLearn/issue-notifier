package ru.my.model;

/**
 * Как поступать с текстом комментария в уведомлениях — глобальная настройка
 * администратора ({@link #KEY}).
 * <p>
 * Три состояния вместо галки: администратору нужно уметь не только запретить
 * текст, но и наоборот выдать его всем, не оставляя выбора пользователю.
 */
public enum CommentTextMode {

    /** Текста нет ни у кого; галки в настройках пользователя нет. */
    HIDDEN("hidden"),

    /** Текст есть у всех; личная настройка пользователя игнорируется. */
    SHOWN("shown"),

    /** Разрешены оба варианта — выбирает сам пользователь. */
    USER("user");

    /** Ключ настройки в {@link ru.my.api.AdminSettingsService}. */
    public static final String KEY = "comment.textMode";

    private final String key;

    CommentTextMode(String key) {
        this.key = key;
    }

    /** Значение настройки, например {@code "user"}. */
    public String key() {
        return key;
    }

    /** Режим по {@link #key()}; {@code null} — неизвестное значение. */
    public static CommentTextMode byKey(String key) {
        for (CommentTextMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * Режим из настроек. Пока записи нет, значение берётся из старой галки
     * {@link ActionTemplates#HIDE_COMMENT_TEXT_KEY}: обновление плагина не должно
     * менять поведение уже настроенной инсталляции.
     *
     * @param raw             значение {@link #KEY}; пустое — записи нет
     * @param legacyHideText  значение старого ключа: {@code "true"} — текст был запрещён
     */
    public static CommentTextMode resolve(String raw, String legacyHideText) {
        CommentTextMode mode = byKey(raw);
        if (mode != null) {
            return mode;
        }
        return Boolean.parseBoolean(legacyHideText) ? HIDDEN : USER;
    }
}
