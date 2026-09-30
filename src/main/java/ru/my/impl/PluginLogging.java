package ru.my.impl;

import com.atlassian.jira.config.util.JiraHome;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.impl.email.EmailMessageFormatter;
import ru.my.impl.email.EmailNotificationSender;
import ru.my.impl.mattermost.MattermostClient;
import ru.my.impl.mattermost.MattermostMessageFormatter;
import ru.my.impl.mattermost.MattermostNotificationSender;
import ru.my.impl.telegram.TelegramClient;
import ru.my.impl.telegram.TelegramMessageFormatter;
import ru.my.impl.telegram.TelegramNotificationSender;
import ru.my.impl.telegram.TelegramPollingService;
import ru.my.model.LoggingSettings;
import ru.my.model.LoggingSettings.Area;
import ru.my.rest.UserSettingsResource;
import ru.my.servlet.AdminSettingsServlet;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Свой файл лога и ручка подробного логирования.
 * <p>
 * Логирование Jira 9.x — это Log4j 2, а slf4j в плагине пишет через него же. Поэтому
 * плагину не нужна обёртка над логгером: код пишет обычные {@code log.debug(...)},
 * а ручка в админке просто меняет уровень логгера {@code ru.my}. Выключенный DEBUG
 * не стоит ничего — log4j отбрасывает запись до форматирования аргументов.
 * <p>
 * Логгер {@code ru.my} получает собственный файл {@code <jira-home>/log/issue-notifier.log}
 * и {@code additivity=false}, чтобы подробный лог не заливал atlassian-jira.log. Вторая
 * ссылка — на штатный appender Jira с порогом WARN: проблемы плагина остаются видны там,
 * где администратор их привык искать (и попадают в support-архив).
 * <p>
 * Пакеты {@code org.apache.logging.log4j.core.*} импортируются как optional (см. pom):
 * если хост их не отдаёт, плагин работает, а лог просто идёт в общий файл Jira.
 */
@Named
public class PluginLogging {

    private static final Logger log = LoggerFactory.getLogger(PluginLogging.class);

    /** Общий корень всех классов плагина — на него и вешаем appender. */
    private static final String PLUGIN_LOGGER = "ru.my";
    private static final String APPENDER_NAME = "issue-notifier";
    /** Appender Jira для atlassian-jira.log, см. WEB-INF/classes/log4j2.xml. */
    private static final String JIRA_APPENDER = "filelog";

    private static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{1}] %X{jira.username} %m%n";
    private static final String MAX_SIZE = "20 MB";
    private static final String KEEP_FILES = "5";

    private final JiraHome jiraHome;

    @Inject
    public PluginLogging(@ComponentImport JiraHome jiraHome) {
        this.jiraHome = jiraHome;
        attach();
    }

    /**
     * Применяет включённые области логирования. Вызывается при старте и при каждом
     * сохранении настроек — уровни меняются сразу, перезапуск Jira не нужен.
     * <p>
     * Область без галочки получает INFO, а не «наследование от родителя»: иначе
     * включённая {@link Area#ALL} не оставила бы способа приглушить шумную область.
     */
    public void applyAreas(Set<Area> enabled) {
        boolean all = enabled.contains(Area.ALL);
        try {
            LoggerContext context = context();
            Configuration configuration = context.getConfiguration();

            setLevel(configuration, PLUGIN_LOGGER, all ? Level.DEBUG : Level.INFO);
            for (Area area : Area.values()) {
                if (area == Area.ALL) {
                    continue;
                }
                Level level = all || enabled.contains(area) ? Level.DEBUG : Level.INFO;
                for (String logger : loggers(area)) {
                    setLevel(configuration, logger, level);
                }
            }
            context.updateLoggers();
            log.info("Логирование включено для: {}", enabled.isEmpty()
                    ? "важных событий и ошибок"
                    : enabled.stream().map(Area::label).collect(Collectors.joining(", ")));
        } catch (Exception | LinkageError e) {
            log.warn("Не удалось изменить уровни логирования: {}", e.toString());
        }
    }

    /**
     * Логгеры области — по именам классов, а не строками: так переименование класса
     * ломает сборку, а не тихо отключает галочку в админке.
     */
    private static List<String> loggers(Area area) {
        switch (area) {
            case CHANNELS:
                return names(MattermostClient.class, MattermostNotificationSender.class,
                        MattermostMessageFormatter.class, TelegramClient.class,
                        TelegramNotificationSender.class, TelegramMessageFormatter.class,
                        EmailNotificationSender.class, EmailMessageFormatter.class);
            case RECIPIENTS:
                return names(NotificationServiceImpl.class, IssueEventListener.class,
                        IssueRecipients.class, DelegationServiceImpl.class,
                        UserSettingsServiceImpl.class, CommentVisibility.class);
            case SCHEDULER:
                return names(TelegramPollingService.class);
            case REST:
                // здесь классов много и они однородные — берём пакеты целиком
                return List.of(UserSettingsResource.class.getPackageName(),
                        AdminSettingsServlet.class.getPackageName());
            default:
                return List.of();
        }
    }

    private static List<String> names(Class<?>... classes) {
        return Arrays.stream(classes).map(Class::getName).collect(Collectors.toList());
    }

    /** Ставит уровень логгеру, создавая свой {@code LoggerConfig}, если его ещё нет. */
    private static void setLevel(Configuration configuration, String logger, Level level) {
        LoggerConfig loggerConfig = configuration.getLoggerConfig(logger);
        if (logger.equals(loggerConfig.getName())) {
            loggerConfig.setLevel(level);
            return;
        }
        // additivity=true: запись поднимается до ru.my, где её ждёт наш appender,
        // и дальше не идёт — у ru.my additivity уже выключен
        configuration.addLogger(logger, new LoggerConfig(logger, level, true));
    }

    private void attach() {
        try {
            LoggerContext context = context();
            // бандл мог упасть, не дойдя до destroy() — иначе останется appender
            // прошлой загрузки с открытым файлом
            detach(context);
            Configuration configuration = context.getConfiguration();
            File file = new File(jiraHome.getLogDirectory(), LoggingSettings.LOG_FILE_NAME);

            RollingFileAppender fileAppender = RollingFileAppender.newBuilder()
                    .setName(APPENDER_NAME)
                    .withFileName(file.getAbsolutePath())
                    // .1 — самый свежий из отложенных, дальше по возрасту
                    .withFilePattern(file.getAbsolutePath() + ".%i")
                    .withPolicy(SizeBasedTriggeringPolicy.createPolicy(MAX_SIZE))
                    .withStrategy(DefaultRolloverStrategy.newBuilder().withMax(KEEP_FILES).build())
                    .setLayout(PatternLayout.newBuilder().withPattern(PATTERN).build())
                    .setConfiguration(configuration)
                    .build();
            fileAppender.start();
            configuration.addAppender(fileAppender);

            LoggerConfig loggerConfig = configuration.getLoggerConfig(PLUGIN_LOGGER);
            if (!PLUGIN_LOGGER.equals(loggerConfig.getName())) {
                loggerConfig = new LoggerConfig(PLUGIN_LOGGER, Level.INFO, false);
                configuration.addLogger(PLUGIN_LOGGER, loggerConfig);
            }
            loggerConfig.setAdditive(false);
            loggerConfig.addAppender(fileAppender, null, null);

            // WARN и ERROR дублируем в общий лог Jira — остальное туда не идёт
            Appender jiraLog = configuration.getAppender(JIRA_APPENDER);
            if (jiraLog != null) {
                loggerConfig.addAppender(jiraLog, Level.WARN, null);
            }

            context.updateLoggers();
            log.info("Лог плагина: {}", file.getAbsolutePath());
        } catch (Exception | LinkageError e) {
            // без своего файла плагин работоспособен — пишем в общий лог Jira
            log.warn("Не удалось настроить отдельный файл лога, лог идёт в atlassian-jira.log: {}",
                    e.toString());
        }
    }

    /**
     * Снимает appender при выгрузке бандла. Без этого QuickReload за сессию оставил бы
     * десятки живых appender'ов, каждый со своим открытым файловым хендлом.
     */
    @PreDestroy
    public void destroy() {
        try {
            detach(context());
        } catch (Exception | LinkageError e) {
            log.warn("Не удалось снять appender плагина: {}", e.toString());
        }
    }

    /** Снимает appender плагина и закрывает его файл. Безопасно вызывать, когда его нет. */
    private void detach(LoggerContext context) {
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = configuration.getLoggerConfig(PLUGIN_LOGGER);
        if (PLUGIN_LOGGER.equals(loggerConfig.getName())) {
            loggerConfig.removeAppender(APPENDER_NAME);
        }
        Appender previous = configuration.getAppender(APPENDER_NAME);
        if (configuration instanceof AbstractConfiguration) {
            ((AbstractConfiguration) configuration).removeAppender(APPENDER_NAME);
        }
        if (previous != null) {
            previous.stop();
        }
        context.updateLoggers();
    }

    /**
     * Контекст берём через загрузчик самого {@code LogManager}, а не через
     * {@code getContext(false)}: селектор log4j выбирает контекст по загрузчику
     * вызывающего класса, а у OSGi-бандла он свой. Так мы гарантированно правим тот
     * же контекст, что и Jira (так же делает её собственный ViewLogging).
     */
    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(LogManager.class.getClassLoader(), false);
    }
}
