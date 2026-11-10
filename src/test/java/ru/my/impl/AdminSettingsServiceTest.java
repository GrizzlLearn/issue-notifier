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
    @Mock
    private com.atlassian.beehive.ClusterLockService clusterLockService;
    @Mock
    private com.atlassian.beehive.ClusterLock clusterLock;

    private AutoCloseable mocks;
    private AdminSettingsServiceImpl service;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        var sharedLock = new java.util.concurrent.locks.ReentrantLock();
        doAnswer(call -> { sharedLock.lock(); return null; }).when(clusterLock).lock();
        doAnswer(call -> { sharedLock.unlock(); return null; }).when(clusterLock).unlock();
        when(clusterLockService.getLockForName(anyString())).thenReturn(clusterLock);
        service = new AdminSettingsServiceImpl(ao, new MemoryCacheManager(), pluginLogging, clusterLockService);
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
    @Test
    public void invalidationCannotLeaveAnInFlightOldLoadCached() throws Exception {
        var cacheManager = spy(new MemoryCacheManager());
        service = new AdminSettingsServiceImpl(ao, cacheManager, pluginLogging, clusterLockService);
        verify(cacheManager).getCache(anyString(),
                org.mockito.ArgumentMatchers.<com.atlassian.cache.CacheLoader<String, String>>notNull(),
                any(com.atlassian.cache.CacheSettings.class));
        var loadedOldValue = new java.util.concurrent.CountDownLatch(1);
        var releaseLoad = new java.util.concurrent.CountDownLatch(1);
        var databaseUpdated = new java.util.concurrent.CountDownLatch(1);
        var value = new java.util.concurrent.atomic.AtomicReference<>("old");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        when(ao.executeInTransaction(any())).thenAnswer(call ->
                ((com.atlassian.sal.api.transaction.TransactionCallback<?>) call.getArgument(0)).doInTransaction());
        when(row.getSettingValue()).thenAnswer(call -> {
            String snapshot = value.get();
            if (reads.getAndIncrement() == 0) {
                loadedOldValue.countDown();
                assertTrue(releaseLoad.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            return snapshot;
        });
        doAnswer(call -> {
            value.set(call.getArgument(0));
            databaseUpdated.countDown();
            return null;
        }).when(row).setSettingValue(anyString());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reader = pool.submit(() -> service.get("key", ""));
            assertTrue(loadedOldValue.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var writer = pool.submit(() -> service.set("key", "new"));
            assertTrue(databaseUpdated.await(5, java.util.concurrent.TimeUnit.SECONDS));
            releaseLoad.countDown();
            reader.get(5, java.util.concurrent.TimeUnit.SECONDS);
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("new", service.get("key", ""));
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void compareAndSetRejectsStaleApplicationCacheUsingFreshDatabaseRead() {
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(row.getSettingValue()).thenReturn("old");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        assertEquals("old", service.get("key", ""));
        when(row.getSettingValue()).thenReturn("other-node");
        assertFalse(service.compareAndSet("key", "old", "mine"));
        assertEquals("other-node", service.get("key", ""));
        verify(ao, times(3)).find(eq(AdminSettingsEntity.class), any(Query.class));
        verify(ao, never()).executeInTransaction(any());
        verify(row, never()).save();
    }

    @Test
    public void successfulCompareAndSetInvalidatesCachedValue() {
        var value = new java.util.concurrent.atomic.AtomicReference<>("old");
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(row.getSettingValue()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; })
                .when(row).setSettingValue(anyString());
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        when(ao.executeInTransaction(any())).thenAnswer(call ->
                ((com.atlassian.sal.api.transaction.TransactionCallback<?>) call.getArgument(0)).doInTransaction());
        assertEquals("old", service.get("key", ""));
        assertTrue(service.compareAndSet("key", "old", "new"));
        assertEquals("new", service.get("key", ""));
        verify(row).save();
    }

    @Test
    public void twoServiceInstancesCannotBothSaveTheSameExpectedValue() throws Exception {
        var value = new java.util.concurrent.atomic.AtomicReference<>("old");
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(row.getSettingValue()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; })
                .when(row).setSettingValue(anyString());
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        when(ao.executeInTransaction(any())).thenAnswer(call ->
                ((com.atlassian.sal.api.transaction.TransactionCallback<?>) call.getArgument(0)).doInTransaction());
        var second = new AdminSettingsServiceImpl(ao, new MemoryCacheManager(), pluginLogging, clusterLockService);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return service.compareAndSet("key", "old", "a"); });
            var b = pool.submit(() -> { start.await(); return second.compareAndSet("key", "old", "b"); });
            start.countDown();
            assertNotEquals(a.get(5, java.util.concurrent.TimeUnit.SECONDS), b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            verify(row, times(1)).save();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void compareAndSetUnlocksWhenStoreFails() {
        AdminSettingsEntity row = mock(AdminSettingsEntity.class);
        when(row.getSettingValue()).thenReturn("old");
        when(ao.find(eq(AdminSettingsEntity.class), any(Query.class)))
                .thenReturn(new AdminSettingsEntity[]{row});
        RuntimeException failure = new IllegalStateException("database failure");
        when(ao.executeInTransaction(any())).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.compareAndSet("key", "old", "new")));
        var ordered = inOrder(clusterLock);
        ordered.verify(clusterLock).lock();
        ordered.verify(clusterLock).unlock();
    }

}
