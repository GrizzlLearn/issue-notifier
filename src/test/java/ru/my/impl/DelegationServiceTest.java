package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.MockApplicationUser;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.NotificationDelegationEntity;
import ru.my.model.DelegationInfo;

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

    private DelegationServiceImpl service;

    private final ApplicationUser alice = new MockApplicationUser("alice");
    private final ApplicationUser bob = new MockApplicationUser("bob");

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DelegationServiceImpl(ao, userManager);
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
        NotificationDelegationEntity entity = activeDelegation("alice", "bob", null);
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
        Date yesterday = new Date(System.currentTimeMillis() - 86_400_000);
        NotificationDelegationEntity entity = activeDelegation("alice", "bob", yesterday);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(alice, result);
        verifyNoInteractions(userManager);
    }

    @Test
    public void returnsOriginalUserWhenDelegateNotFoundInJira() {
        NotificationDelegationEntity entity = activeDelegation("alice", "deleted-user", null);
        when(ao.find(eq(NotificationDelegationEntity.class), any(Query.class)))
                .thenReturn(new NotificationDelegationEntity[]{entity});
        when(userManager.getUserByKey("deleted-user")).thenReturn(null);

        ApplicationUser result = service.getEffectiveRecipient(alice);

        assertSame(alice, result);
    }

    @Test
    public void getDelegationReturnsPresentWhenRecordExists() {
        Date tomorrow = new Date(System.currentTimeMillis() + 86_400_000);
        NotificationDelegationEntity entity = activeDelegation("alice", "bob", tomorrow);
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

    private NotificationDelegationEntity activeDelegation(String from, String to, Date activeUntil) {
        NotificationDelegationEntity entity = mock(NotificationDelegationEntity.class);
        when(entity.getFromUserKey()).thenReturn(from);
        when(entity.getToUserKey()).thenReturn(to);
        when(entity.getActiveUntil()).thenReturn(activeUntil);
        return entity;
    }
}
