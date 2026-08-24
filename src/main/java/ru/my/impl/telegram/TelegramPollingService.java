package ru.my.impl.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Опрашивает Telegram Bot API (getUpdates) каждые 5 секунд.
 * При получении команды /start отвечает пользователю его chat_id,
 * чтобы он мог вставить его в настройки Jira.
 */
@Named
public class TelegramPollingService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    // "chat":{"id":-123} или "chat":{"id":456}
    private static final Pattern P_UPDATE_ID = Pattern.compile("\"update_id\"\\s*:\\s*(\\d+)");
    private static final Pattern P_CHAT_ID   = Pattern.compile("\"chat\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(-?\\d+)");

    private final TelegramClient client;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong offset = new AtomicLong(0);

    @Inject
    public TelegramPollingService(TelegramClient client) {
        this.client = client;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-polling");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollOnce, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    void pollOnce() {
        try {
            String json = client.getUpdates(offset.get());
            // Разбиваем на чанки по каждому update_id
            for (String chunk : json.split("(?=\"update_id\")")) {
                Matcher uid = P_UPDATE_ID.matcher(chunk);
                if (!uid.find()) continue;
                long next = Long.parseLong(uid.group(1)) + 1;
                offset.updateAndGet(v -> Math.max(v, next));

                // Обрабатываем только /start (включая /start@BotName в группах)
                if (!chunk.contains("\"/start")) continue;

                Matcher cid = P_CHAT_ID.matcher(chunk);
                if (!cid.find()) continue;
                String chatId = cid.group(1);

                client.sendMessage(chatId,
                        "Ваш Telegram chat_id: <code>" + chatId + "</code>\n\n" +
                        "Скопируйте это число в настройки уведомлений Jira.");
                log.debug("Ответили на /start в чате {}", chatId);
            }
        } catch (Exception e) {
            log.warn("Ошибка опроса Telegram getUpdates: {}", e.getMessage());
        }
    }
}
