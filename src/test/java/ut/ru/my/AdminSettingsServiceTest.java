package ut.ru.my;

import com.atlassian.activeobjects.external.ActiveObjects;
import net.java.ao.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.my.ao.AdminSettingsEntity;
import ru.my.impl.AdminSettingsServiceImpl;
import ru.my.model.NotificationChannel;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AdminSettingsServiceTest {

    @Mock
    private ActiveObjects ao;

    private AdminSettingsServiceImpl service;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AdminSettingsServiceImpl(ao);
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
}
