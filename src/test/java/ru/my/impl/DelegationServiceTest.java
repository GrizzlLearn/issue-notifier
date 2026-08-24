package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import net.java.ao.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.NotificationDelegationEntity;
import ru.my.model.DelegationInfo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
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

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new DelegationServiceImpl(ao, userManager);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void returnsOriginalUserWhenNoDelegationExists() {
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[0]);

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(alice, result);
    }

    @Test
    public void returnsDelegateWhenDelegationIsActive() {
        NotificationDelegationEntity entity = delegationEntity("bob", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("bob")).thenReturn(bob);

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(bob, result);
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

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(alice, result);
        verifyNoInteractions(userManager);
    }

    @Test
    public void returnsOriginalUserWhenDelegateNotFoundInJira() {
        NotificationDelegationEntity entity = delegationEntity("deleted-user", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("deleted-user")).thenReturn(null);

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(alice, result);
    }

    @Test
    public void getDelegationReturnsPresentWhenRecordExists() {
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        NotificationDelegationEntity entity = delegationEntity("bob", tomorrow);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});

        Optional<DelegationInfo> result = service.getDelegation(alice);

        assertTrue(result.isPresent());
        assertEquals("bob", result.get().getToUserKey());
        assertTrue(result.get().isActive());
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
        service.setDelegation(alice, alice, null);
    }

    private NotificationDelegationEntity delegationEntity(String toUserKey, Instant activeUntil) {
        NotificationDelegationEntity entity = mock(NotificationDelegationEntity.class);
        when(entity.getToUserKey()).thenReturn(toUserKey);
        // AO возвращает java.util.Date — имитируем конвертацию на границе слоя
        when(entity.getActiveUntil()).thenReturn(activeUntil != null ? Date.from(activeUntil) : null);
        return entity;
    }
}
