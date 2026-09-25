package ru.my.impl.telegram;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationChannel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Опрашивает Telegram Bot API (getUpdates) каждые 5 секунд.
 * При получении команды /start отвечает пользователю его chat_id,
 * чтобы он мог вставить его в настройки Jira.
 * <p>
 * Задание кластерное ({@link RunMode#RUN_ONCE_PER_CLUSTER}): токен у бота один,
 * и {@code getUpdates} на нескольких нодах разобрал бы очередь между собой —
 * апдейт доставался бы случайной ноде, а у остальных offset уезжал бы вперёд,
 * и часть {@code /start} осталась бы без ответа. По той же причине offset хранится
 * в настройках, а не в памяти: задание может выполниться на любой ноде.
 */
@Named
public class TelegramPollingService implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private static final JobRunnerKey RUNNER_KEY = JobRunnerKey.of("ru.my.issue-notifier.telegram-polling");
    private static final JobId JOB_ID = JobId.of("ru.my.issue-notifier.telegram-polling");
    private static final long INTERVAL_MS = 5_000;

    /** Первый опрос не сразу: на старте плагина AO и настройки ещё поднимаются. */
    private static final long INITIAL_DELAY_MS = 30_000;

    /** Ключ настройки с offset — переживает перезапуск и переезд задания на другую ноду. */
    static final String OFFSET_KEY = "telegram.pollOffset";

    // "chat":{"id":-123} или "chat":{"id":456}
    private static final Pattern P_UPDATE_ID = Pattern.compile("\"update_id\"\\s*:\\s*(\\d+)");
    private static final Pattern P_CHAT_ID   = Pattern.compile("\"chat\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(-?\\d+)");
    // команда — это начало поля text, а не любое вхождение "/start в апдейте:
    // иначе срабатывала бы и цитата чужого сообщения
    private static final Pattern P_START     = Pattern.compile("\"text\"\\s*:\\s*\"/start\\b");

    private final TelegramClient client;
    private final AdminSettingsService adminSettings;
    private final SchedulerService schedulerService;

    @Inject
    public TelegramPollingService(TelegramClient client,
                                  AdminSettingsService adminSettings,
                                  @ComponentImport SchedulerService schedulerService) {
        this.client = client;
        this.adminSettings = adminSettings;
        this.schedulerService = schedulerService;
    }

    @PostConstruct
    public void start() {
        schedulerService.registerJobRunner(RUNNER_KEY, this);
        try {
            schedulerService.scheduleJob(JOB_ID, JobConfig.forJobRunnerKey(RUNNER_KEY)
                    .withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
                    .withSchedule(Schedule.forInterval(
                            INTERVAL_MS, new Date(System.currentTimeMillis() + INITIAL_DELAY_MS))));
        } catch (SchedulerServiceException e) {
            log.error("Не удалось запланировать опрос Telegram: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(RUNNER_KEY);
    }

    @Nullable
    @Override
    public JobRunnerResponse runJob(@Nonnull JobRunnerRequest request) {
        pollOnce();
        return JobRunnerResponse.success();
    }

    void pollOnce() {
        // канал выключен администратором — бот не отвечает и на /start
        if (!adminSettings.isChannelEnabled(NotificationChannel.TELEGRAM)) {
            return;
        }
        try {
            long offset = readOffset();
            long nextOffset = offset;
            String json = client.getUpdates(offset);
            // Разбиваем на чанки по каждому update_id
            for (String chunk : json.split("(?=\"update_id\")")) {
                Matcher uid = P_UPDATE_ID.matcher(chunk);
                if (!uid.find()) continue;
                nextOffset = Math.max(nextOffset, Long.parseLong(uid.group(1)) + 1);

                // Обрабатываем только /start (включая /start@BotName в группах)
                if (!P_START.matcher(chunk).find()) continue;

                Matcher cid = P_CHAT_ID.matcher(chunk);
                if (!cid.find()) continue;
                String chatId = cid.group(1);

                client.sendMessage(chatId,
                        "Ваш Telegram chat_id: <code>" + chatId + "</code>\n\n" +
                        "Скопируйте это число в настройки уведомлений Jira.");
                log.debug("Ответили на /start в чате {}", chatId);
            }
            // пишем только когда offset реально сдвинулся — иначе запись в БД на каждый опрос
            if (nextOffset != offset) {
                adminSettings.set(OFFSET_KEY, String.valueOf(nextOffset));
            }
        } catch (Exception e) {
            log.warn("Ошибка опроса Telegram getUpdates: {}", e.getMessage());
        }
    }

    private long readOffset() {
        String raw = adminSettings.get(OFFSET_KEY, "0");
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Некорректный offset '{}' в настройках, начинаем с нуля", raw);
            return 0;
        }
    }
}
