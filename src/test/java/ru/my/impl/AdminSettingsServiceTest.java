package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import net.java.ao.Query;
import org.junit.After;
import com.atlassian.cache.memory.MemoryCacheManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.AdminSettingsEntity;
import ru.my.model.LoggingSettings.Area;
import ru.my.model.NotificationChannel;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AdminSettingsServiceTest {

    @Mock
    private ActiveObjects ao;
    @Mock
    private PluginLogging pluginLogging;

    private AutoCloseable mocks;
    private AdminSettingsServiceImpl service;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new AdminSettingsServiceImpl(ao, new MemoryCacheManager(), pluginLogging);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void returnsDefaultValueWhenKeyNotFound() {
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0]);

        String result = service.get("mattermost.botToken", "default");

        assertEquals("default", result);
    }

    @Test
    public void returnsStoredValue() {
        AdminSettingsEntity entity = mock(AdminSettingsEntity.class);
        when(entity.getSettingValue()).thenReturn("https://mm.example.com");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{entity});

        String result = service.get("mattermost.domain", "");

        assertEquals("https://mm.example.com", result);
    }

    @Test
    public void channelIsDisabledByDefault() {
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0]);

        assertFalse(service.isChannelEnabled(NotificationChannel.MATTERMOST));
        assertFalse(service.isChannelEnabled(NotificationChannel.TELEGRAM));
        assertFalse(service.isChannelEnabled(NotificationChannel.EMAIL));
    }

    @Test
    public void channelIsEnabledWhenSettingIsTrue() {
        AdminSettingsEntity entity = mock(AdminSettingsEntity.class);
        when(entity.getSettingValue()).thenReturn("true");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{entity});

        assertTrue(service.isChannelEnabled(NotificationChannel.EMAIL));
    }

    /** Второй вызов get() с тем же ключом не должен обращаться к АО повторно. */
    @Test
    public void secondGetHitsCacheNotDatabase() {
        AdminSettingsEntity entity = mock(AdminSettingsEntity.class);
        when(entity.getSettingValue()).thenReturn("https://mm.example.com");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{entity});

        service.get("mattermost.domain", "");
        service.get("mattermost.domain", "");

        verify(ao, times(1)).find(eq(AdminSettingsEntity.class), any(Query.class));
    }

    /** После set() следующий get() должен снова пойти в АО (кеш инвалидирован). */
    @Test
    public void getAfterSetBypassesCache() {
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0]);
        when(ao.executeInTransaction(any())).thenReturn(null);

        service.get("some.key", "");
        service.set("some.key", "value");
        service.get("some.key", "");

        verify(ao, times(2)).find(eq(AdminSettingsEntity.class), any(Query.class));
    }

    /** Ни одна область не включена — применяется пустой набор, то есть только INFO и выше. */
    @Test
    public void appliesEmptyAreaSetWhenNothingEnabled() {
        when(ao.executeInTransaction(any())).thenReturn(null);
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[0]);

        service.set(Area.CHANNELS.key(), "false");

        verify(pluginLogging).applyAreas(Set.of());
    }

    /**
     * Сохранение галочки области применяет весь набор включённых областей, а не
     * только изменённую: иначе снятая ранее галочка осталась бы действовать.
     */
    @Test
    public void appliesAllEnabledAreasTogether() {
        when(ao.executeInTransaction(any())).thenReturn(null);
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(row.getSettingValue()).thenReturn("true");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});

        service.set(Area.REST.key(), "true");

        verify(pluginLogging).applyAreas(EnumSet.allOf(Area.class));
    }

    /** Остальные настройки уровень логирования не трогают. */
    @Test
    public void otherSettingsDoNotTouchLoggingLevel() {
        when(ao.executeInTransaction(any())).thenReturn(null);

        service.set("mattermost.domain", "https://mm.example.com");

        verifyNoInteractions(pluginLogging);
    }
}
