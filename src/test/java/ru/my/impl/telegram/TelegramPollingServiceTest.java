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

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(adminSettings.isChannelEnabled(NotificationChannel.TELEGRAM)).thenReturn(true);
        // offset живёт в настройках: задание кластерное и может уехать на другую ноду
        when(adminSettings.get(TelegramPollingService.OFFSET_KEY, "0")).thenReturn("0");
        service = new TelegramPollingService(client, adminSettings, schedulerService);
    }

    @After
    public void tearDown() throws Exception {
        service.destroy();
        mocks.close();
    }

    @Test
    public void repliesWithChatIdOnStart() {
        when(client.getUpdates(0)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":100," +
                "\"message\":{\"chat\":{\"id\":123456},\"text\":\"/start\"}}]}");

        service.pollOnce();

        verify(client).sendMessage(eq("123456"), contains("123456"));
    }

    @Test
    public void updatesOffsetAfterProcessing() {
        when(client.getUpdates(0)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":200," +
                "\"message\":{\"chat\":{\"id\":999},\"text\":\"/start\"}}]}");
        when(client.getUpdates(201)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();
        verify(adminSettings).set(TelegramPollingService.OFFSET_KEY, "201");

        when(adminSettings.get(TelegramPollingService.OFFSET_KEY, "0")).thenReturn("201");
        service.pollOnce();

        verify(client).getUpdates(0);
        verify(client).getUpdates(201);
    }

    @Test
    public void ignoresNonStartMessages() {
        when(client.getUpdates(0)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":300," +
                "\"message\":{\"chat\":{\"id\":777},\"text\":\"hello\"}}]}");

        service.pollOnce();

        verify(client, never()).sendMessage(any(), any());
    }

    @Test
    public void handlesStartWithBotName() {
        when(client.getUpdates(0)).thenReturn(
                "{\"ok\":true,\"result\":[{\"update_id\":400," +
                "\"message\":{\"chat\":{\"id\":555},\"text\":\"/start@MyJiraBot\"}}]}");

        service.pollOnce();

        verify(client).sendMessage(eq("555"), contains("555"));
    }

    @Test
    public void handlesEmptyResultGracefully() {
        when(client.getUpdates(0)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();

        verify(client, never()).sendMessage(any(), any());
    }

    @Test
    public void skipsPollingWhenChannelDisabled() {
        when(adminSettings.isChannelEnabled(NotificationChannel.TELEGRAM)).thenReturn(false);

        service.pollOnce();

        verify(client, never()).getUpdates(anyLong());
    }

    /** Настройку могли поправить руками — битый offset не должен ронять опрос. */
    @Test
    public void startsFromZeroWhenStoredOffsetIsGarbage() {
        when(adminSettings.get(TelegramPollingService.OFFSET_KEY, "0")).thenReturn("не число");
        when(client.getUpdates(0)).thenReturn("{\"ok\":true,\"result\":[]}");

        service.pollOnce();

        verify(client).getUpdates(0);
    }

    @Test
    public void swallowsExceptionFromClient() {
        when(client.getUpdates(0)).thenThrow(new TelegramClient.TelegramException("network error"));

        service.pollOnce(); // не должен бросать исключение
    }
}
