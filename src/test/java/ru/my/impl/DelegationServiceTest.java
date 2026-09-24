package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import net.java.ao.Query;
import org.junit.After;
import com.atlassian.cache.memory.MemoryCacheManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.NotificationDelegationEntity;
import ru.my.model.DelegationInfo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class DelegationServiceTest {

    @Mock
    private ActiveObjects ao;
    @Mock
    private UserManager userManager;

    private AutoCloseable mocks;
    private DelegationServiceImpl service;

    private final ApplicationUser alice = new MockApplicationUser("alice");
    private final ApplicationUser bob = new MockApplicationUser("bob");
    private final ApplicationUser carol = new MockApplicationUser("carol");

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new DelegationServiceImpl(ao, userManager, new MemoryCacheManager());
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void returnsOriginalUserWhenNoDelegationExists() {
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[0]);

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(alice), result);
    }

    @Test
    public void returnsDelegateWhenDelegationIsActive() {
        NotificationDelegationEntity entity = delegationEntity("bob", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("bob")).thenReturn(bob);

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(bob), result);
    }

    /** Делегация на нескольких получателей — все активные делегаты должны вернуться. */
    @Test
    public void returnsAllDelegatesWhenMultipleAreSet() {
        NotificationDelegationEntity entity = delegationEntity("bob,carol", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("bob")).thenReturn(bob);
        when(userManager.getUserByKey("carol")).thenReturn(carol);

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(bob, carol), result);
    }

    /** Если один из делегатов удалён из Jira — пропускаем его, но остальные возвращаем. */
    @Test
    public void skipsMissingDelegateButKeepsOthers() {
        NotificationDelegationEntity entity = delegationEntity("deleted-user,carol", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("deleted-user")).thenReturn(null);
        when(userManager.getUserByKey("carol")).thenReturn(carol);

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(carol), result);
    }

    /**
     * Если activeUntil в прошлом — делегация истекла,
     * уведомления возвращаются оригинальному пользователю.
     */
    @Test
    public void returnsOriginalUserWhenDelegationExpired() {
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        NotificationDelegationEntity entity = delegationEntity("bob", yesterday);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(alice), result);
        verifyNoInteractions(userManager);
    }

    @Test
    public void returnsOriginalUserWhenDelegateNotFoundInJira() {
        NotificationDelegationEntity entity = delegationEntity("deleted-user", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("deleted-user")).thenReturn(null);

        List<ApplicationUser> result = service.getEffectiveRecipients(alice);

        assertEquals(List.of(alice), result);
    }

    @Test
    public void getDelegationReturnsPresentWhenRecordExists() {
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        NotificationDelegationEntity entity = delegationEntity("bob", tomorrow);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});

        Optional<DelegationInfo> result = service.getDelegation(alice);

        assertTrue(result.isPresent());
        assertEquals(List.of("bob"), result.get().getToUserKeys());
        assertTrue(result.get().isActive());
    }

    @Test
    public void getDelegationParsesMultipleKeys() {
        NotificationDelegationEntity entity = delegationEntity("bob, carol", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});

        Optional<DelegationInfo> result = service.getDelegation(alice);

        assertEquals(List.of("bob", "carol"), result.get().getToUserKeys());
    }

    @Test
    public void getDelegationReturnsEmptyWhenNoRecord() {
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[0]);

        Optional<DelegationInfo> result = service.getDelegation(alice);

        assertFalse(result.isPresent());
    }

    /** Делегирование самому себе должно быть отклонено — бессмысленно и маскирует ошибки UI. */
    @Test(expected = IllegalArgumentException.class)
    public void throwsWhenDelegatingToSelf() {
        service.setDelegation(alice, List.of(alice), null);
    }

    /** Делегирование самому себе среди прочих получателей — тоже отклоняется. */
    @Test(expected = IllegalArgumentException.class)
    public void throwsWhenDelegatingToSelfAmongOthers() {
        service.setDelegation(alice, List.of(bob, alice), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsWhenDelegateListIsEmpty() {
        service.setDelegation(alice, List.of(), null);
    }

    private NotificationDelegationEntity delegationEntity(String toUserKeysCsv, Instant activeUntil) {
        NotificationDelegationEntity entity = mock(NotificationDelegationEntity.class);
        when(entity.getToUserKey()).thenReturn(toUserKeysCsv);
        // AO возвращает java.util.Date — имитируем конвертацию на границе слоя
        when(entity.getActiveUntil()).thenReturn(activeUntil != null ? Date.from(activeUntil) : null);
        return entity;
    }
}
