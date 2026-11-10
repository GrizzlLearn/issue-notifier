package ru.my.impl.telegram;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.atlassian.scheduler.SchedulerService;
import ru.my.api.AdminSettingsService;
import ru.my.model.NotificationChannel;

import static org.mockito.Mockito.*;

public class TelegramPollingServiceTest {

    @Mock private TelegramClient client;
    @Mock private AdminSettingsService adminSettings;
    @Mock private SchedulerService schedulerService;
    private AutoCloseable mocks;
    private TelegramPollingService service;
    private static final String TOKEN = "123456:secret-token";
    private static final String OFFSET = TelegramPollingService.OFFSET_KEY + ".123456";

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(adminSettings.get(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn(TOKEN);
        when(adminSettings.isChannelEnabled(NotificationChannel.TELEGRAM)).thenReturn(true);
        // offset живёт в настройках: задание кластерное и может уехать на другую ноду
        when(adminSettings.get(OFFSET, "0")).thenReturn("0");
        service = new TelegramPollingService(client, adminSettings, schedulerService);
    }

    @After
    public void tearDown() throws Exception {
        service.destroy();
        mocks.close();
    }

    @Test
    public void repliesWithChatIdOnStart() {
        when(client.getUpdates(0, TOKEN)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":100," +
                "\"message\":{\"chat\":{\"id\":123456},\"text\":\"/start\"}}]}");

        service.pollOnce();

        verify(client).sendMessage(eq("123456"), contains("123456"), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));
    }

    /**
     * Человек написал боту /start и заблокировал его: ответ падает с 403.
     * offset обязан сдвинуться, иначе тот же апдейт придёт снова и /start
     * перестанет работать у всех.
     */
    @Test
    public void movesOffsetEvenWhenReplyFails() {
        when(client.getUpdates(0, TOKEN)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":300," +
                "\"message\":{\"chat\":{\"id\":777},\"text\":\"/start\"}}]}");
        doThrow(new TelegramClient.TelegramException("Forbidden: bot was blocked by the user"))
                .when(client).sendMessage(eq("777"), anyString(), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));

        service.pollOnce();

        verify(adminSettings).set(OFFSET, "301");
    }

    @Test
    public void updatesOffsetAfterProcessing() {
        when(client.getUpdates(0, TOKEN)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":200," +
                "\"message\":{\"chat\":{\"id\":999},\"text\":\"/start\"}}]}");
        when(client.getUpdates(201, TOKEN)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();
        verify(adminSettings).set(OFFSET, "201");

        when(adminSettings.get(OFFSET, "0")).thenReturn("201");
        service.pollOnce();

        verify(client).getUpdates(0, TOKEN);
        verify(client).getUpdates(201, TOKEN);
    }

    @Test
    public void ignoresNonStartMessages() {
        when(client.getUpdates(0, TOKEN)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":300," +
                "\"message\":{\"chat\":{\"id\":777},\"text\":\"hello\"}}]}");

        service.pollOnce();

        verify(client, never()).sendMessage(any(), any(), anyMap());
    }

    @Test
    public void handlesStartWithBotName() {
        when(client.getUpdates(0, TOKEN)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":400," +
                "\"message\":{\"chat\":{\"id\":555},\"text\":\"/start@MyJiraBot\"}}]}");

        service.pollOnce();

        verify(client).sendMessage(eq("555"), contains("555"), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));
    }

    @Test
    public void handlesEmptyResultGracefully() {
        when(client.getUpdates(0, TOKEN)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();

        verify(client, never()).sendMessage(any(), any(), anyMap());
    }

    @Test
    public void skipsPollingWhenChannelDisabled() {
        when(adminSettings.isChannelEnabled(NotificationChannel.TELEGRAM)).thenReturn(false);

        service.pollOnce();

        verify(client, never()).getUpdates(anyLong(), anyString());
    }

    /** Настройку могли поправить руками — битый offset не должен ронять опрос. */
    @Test
    public void startsFromZeroWhenStoredOffsetIsGarbage() {
        when(adminSettings.get(OFFSET, "0")).thenReturn("не число");
        when(client.getUpdates(0, TOKEN)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();

        verify(client).getUpdates(0, TOKEN);
    }

    @Test
    public void swallowsExceptionFromClient() {
        when(client.getUpdates(0, TOKEN)).thenThrow(new TelegramClient.TelegramException("network error"));

        service.pollOnce(); // не должен бросать исключение
    }

    @Test
    public void ignoresQuotedCommandAndUsesCurrentChatWithReorderedFields() {
        when(client.getUpdates(0, TOKEN)).thenReturn("{\"ok\":true,\"result\":["
                + "{\"update_id\":1,\"message\":{\"reply_to_message\":{\"text\":\"/start\",\"chat\":{\"id\":999}},"
                + "\"text\":\"hello\",\"chat\":{\"id\":1}}},"
                + "{\"update_id\":2,\"message\":{\"reply_to_message\":{\"text\":\"/start\",\"chat\":{\"id\":999}},"
                + "\"text\":\"/sta\\u0072t@MyBot argument\",\"chat\":{\"type\":\"group\",\"id\":-42}}}]}");
        service.pollOnce();
        verify(client).sendMessage(eq("-42"), contains("-42"), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));
        verify(client, times(1)).sendMessage(anyString(), anyString(), anyMap());
        verify(adminSettings).set(OFFSET, "3");
    }

    @Test
    public void rejectsStartLikeWordsAndSkipsInvalidMessageButAdvancesOffset() {
        when(client.getUpdates(0, TOKEN)).thenReturn("{\"ok\":true,\"result\":["
                + "{\"update_id\":1,\"message\":{\"text\":\"/start-other\",\"chat\":{\"id\":1}}},"
                + "{\"update_id\":2,\"message\":{\"text\":\"/start\",\"chat\":{\"id\":\"42\"}}},"
                + "{\"update_id\":3,\"message\":{\"text\":null}},"
                + "{\"update_id\":4,\"callback_query\":{\"message\":{\"text\":\"/start\",\"chat\":{\"id\":999}}}}]}");
        service.pollOnce();
        verify(client, never()).sendMessage(anyString(), anyString(), anyMap());
        verify(adminSettings).set(OFFSET, "5");
    }

    @Test
    public void invalidResponsesDoNotAdvanceOffsetOrSendReplies() {
        for (String json : new String[]{null, "{", "null", "{\"ok\":false,\"result\":[]}",
                "{\"ok\":true,\"result\":null}", "{\"ok\":true,\"result\":{}}",
                "{\"ok\":true,\"result\":[{\"update_id\":\"1\"}]}",
                "{\"ok\":true,\"result\":[{\"update_id\":9223372036854775808}]}",
                "{\"ok\":true,\"result\":[{\"update_id\":1},null]}"}) {
            when(client.getUpdates(0, TOKEN)).thenReturn(json);
            service.pollOnce();
        }
        verify(adminSettings, never()).set(anyString(), anyString());
        verify(client, never()).sendMessage(anyString(), anyString(), anyMap());
    }

    @Test
    public void continuesWithLaterUpdatesAfterReplyFailure() {
        when(client.getUpdates(0, TOKEN)).thenReturn("{\"ok\":true,\"result\":["
                + "{\"update_id\":1,\"message\":{\"text\":\"/start\",\"chat\":{\"id\":1}}},"
                + "{\"update_id\":2,\"message\":{\"text\":\"/start\",\"chat\":{\"id\":2}}}]}");
        doThrow(new TelegramClient.TelegramException("blocked")).when(client).sendMessage(eq("1"), anyString(), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));
        service.pollOnce();
        verify(client).sendMessage(eq("2"), anyString(), eq(java.util.Map.of(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, TOKEN)));
        verify(adminSettings).set(OFFSET, "3");
    }

    @Test
    public void stoppingLocalRunnerKeepsSharedSchedule() {
        service.destroy();
        verify(schedulerService, never()).unscheduleJob(any());
        verify(schedulerService).unregisterJobRunner(any());
    }

    @Test
    public void switchingBotsDoesNotReuseOldOffset() {
        when(adminSettings.get(OFFSET, "0")).thenReturn("9999");
        when(adminSettings.get(ru.my.model.ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("654321:other-secret");
        when(adminSettings.get(TelegramPollingService.OFFSET_KEY + ".654321", "0")).thenReturn("0");
        when(client.getUpdates(0, "654321:other-secret")).thenReturn("{\"ok\":true,\"result\":[{\"update_id\":1}]}");
        service.pollOnce();
        verify(client).getUpdates(0, "654321:other-secret");
        verify(adminSettings).set(TelegramPollingService.OFFSET_KEY + ".654321", "2");
    }

}
