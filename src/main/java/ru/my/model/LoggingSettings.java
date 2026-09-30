package ru.my.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Настройки логирования плагина в {@link ru.my.api.AdminSettingsService}.
 * <p>
 * Логирование включается по областям: в log4j каждая область — это ветка логгеров,
 * поэтому «включить область» означает поднять ей уровень до DEBUG, ничего не меняя
 * в остальных. Какие именно логгеры входят в область, знает {@code PluginLogging} —
 * здесь только ключи настроек и подписи для админки.
 */
public final class LoggingSettings {

    private LoggingSettings() {}

    /** Имя файла в {@code <jira-home>/log/} — по названию плагина. */
    public static final String LOG_FILE_NAME = "issue-notifier.log";

    /**
     * Область логирования — отдельная галочка в админке.
     * <p>
     * {@link #ALL} перекрывает остальные: включённая, она поднимает DEBUG всему плагину,
     * и точечные галочки перестают что-либо менять.
     */
    public enum Area {

        ALL("logging.verbose", "Все действия плагина"),
        CHANNELS("logging.channels", "Каналы: обращения к Mattermost, Telegram и почте"),
        RECIPIENTS("logging.recipients", "Отбор получателей и делегирование"),
        REST("logging.rest", "Запросы из интерфейса: настройки, контексты, справочники"),
        SCHEDULER("logging.scheduler", "Планировщик: опрос Telegram");

        private final String key;
        private final String label;

        Area(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** Ключ настройки в {@code ADMIN_SETTINGS}. */
        public String key() {
            return key;
        }

        /** Подпись галочки в админке. */
        public String label() {
            return label;
        }

        /** Область по ключу настройки, если такой ключ относится к логированию. */
        public static Optional<Area> byKey(String key) {
            return Arrays.stream(values()).filter(area -> area.key.equals(key)).findFirst();
        }
    }
}
