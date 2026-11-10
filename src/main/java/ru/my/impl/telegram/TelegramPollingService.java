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
import ru.my.model.ChannelKeys;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import org.codehaus.jackson.JsonNode;
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

    private static final JobRunnerKey RUNNER_KEY = JobRunnerKey.of("ru.noname.atlassian.jira.in.telegram-polling");
    private static final JobId JOB_ID = JobId.of("ru.noname.atlassian.jira.in.telegram-polling");
    private static final long INTERVAL_MS = 5_000;

    /** Первый опрос не сразу: на старте плагина AO и настройки ещё поднимаются. */
    private static final long INITIAL_DELAY_MS = 30_000;

    /** Префикс ключа offset: суффикс — публичный числовой id бота, не секрет токена. */
    static final String OFFSET_KEY = "telegram.pollOffset";

    private static final Pattern START = Pattern.compile("/start(?:@[A-Za-z0-9_]+)?(?:\\s+.*)?", Pattern.DOTALL);

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
            log.info("Опрос Telegram запланирован: каждые {} мс, первый запуск через {} мс",
                    INTERVAL_MS, INITIAL_DELAY_MS);
        } catch (SchedulerServiceException e) {
            log.error("Не удалось запланировать опрос Telegram: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        // Расписание общее: остановка этой ноды не должна выключать остальные.
        schedulerService.unregisterJobRunner(RUNNER_KEY);
        log.info("Опрос Telegram остановлен");
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
            log.debug("Опрос Telegram пропущен — канал выключен администратором");
            return;
        }
        try {
            String token = adminSettings.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "");
            if (token.isBlank()) return;
            String botId = token.split(":", 2)[0];
            if (!botId.matches("[0-9]+")) {
                throw new TelegramClient.TelegramException("Некорректный идентификатор Telegram-бота");
            }
            String offsetKey = OFFSET_KEY + "." + botId;
            long offset = readOffset(offsetKey);
            long nextOffset = offset;
            JsonNode updates = TelegramClient.parseResponse(client.getUpdates(offset, token)).path("result");
            if (!updates.isArray()) {
                throw new TelegramClient.TelegramException("Некорректный ответ getUpdates: ожидается массив result");
            }
            // Без корректного id нельзя безопасно подтвердить эту и последующие записи.
            for (JsonNode update : updates) {
                JsonNode id = update.path("update_id");
                if ((!id.isInt() && !id.isLong())
                        || id.getLongValue() < 0 || id.getLongValue() == Long.MAX_VALUE) {
                    throw new TelegramClient.TelegramException("Некорректный update_id в ответе Telegram");
                }
            }
            for (JsonNode update : updates) {
                nextOffset = Math.max(nextOffset, update.path("update_id").getLongValue() + 1);
                JsonNode message = update.path("message");
                JsonNode text = message.path("text");
                if (!text.isTextual() || !START.matcher(text.getTextValue()).matches()) continue;
                JsonNode chat = message.path("chat").path("id");
                if (!chat.isInt() && !chat.isLong()) {
                    log.warn("Пропущен /start с некорректным chat.id");
                    continue;
                }
                String chatId = String.valueOf(chat.getLongValue());

                // Ошибку ответа глотаем на этом апдейте: иначе offset не сдвинется,
                // тот же апдейт придёт снова, и /start перестанет работать у всех —
                // достаточно одному человеку написать боту и заблокировать его
                try {
                    client.sendMessage(chatId,
                            "Ваш Telegram chat_id: <code>" + chatId + "</code>\n\n" +
                            "Скопируйте это число в настройки уведомлений Jira.",
                            Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, token));
                    log.debug("Ответили на /start в чате {}", chatId);
                } catch (Exception e) {
                    log.warn("Не удалось ответить на /start в чате {}: {}", chatId, e.getMessage());
                }
            }
            // пишем только когда offset реально сдвинулся — иначе запись в БД на каждый опрос
            if (nextOffset != offset) {
                adminSettings.set(offsetKey, String.valueOf(nextOffset));
                log.debug("Опрос Telegram: offset {} → {}", offset, nextOffset);
            }
        } catch (Exception e) {
            log.warn("Ошибка опроса Telegram getUpdates: {}", e.getMessage());
        }
    }

    private long readOffset(String offsetKey) {
        String raw = adminSettings.get(offsetKey, "0");
        try {
            long offset = Long.parseLong(raw.trim());
            return Math.max(0, offset);
        } catch (NumberFormatException e) {
            log.warn("Некорректный offset '{}' в настройках, начинаем с нуля", raw);
            return 0;
        }
    }
}
