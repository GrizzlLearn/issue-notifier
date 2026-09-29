package ru.my.rest;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.MockApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ru.my.api.AdminSettingsService;
import ru.my.api.NotificationSender;
import ru.my.model.ActionTemplates;
import org.mockito.ArgumentCaptor;
import ru.my.model.ChannelKeys;
import ru.my.model.CommentTextMode;
import ru.my.model.NotificationAction;
import ru.my.model.NotificationChannel;
import ru.my.model.TestMessages;

import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class AdminSettingsResourceTest {

    @Mock private JiraAuthenticationContext authContext;
    @Mock private GlobalPermissionManager globalPermissionManager;
    @Mock private AdminSettingsService adminSettingsService;
    @Mock private NotificationSender telegramSender;
    @Mock private com.atlassian.jira.user.util.UserManager userManager;

    private AdminSettingsResource resource;
    /** Канал тестов проверки: единственный зарегистрированный отправщик — Telegram. */
    private static final String TELEGRAM = "TELEGRAM";

    private final MockApplicationUser admin = new MockApplicationUser("admin");
    private final MockApplicationUser regular = new MockApplicationUser("jdoe");

    @Before
    public void setUp() {
        lenient().when(telegramSender.channel()).thenReturn(NotificationChannel.TELEGRAM);
        resource = new AdminSettingsResource(authContext, globalPermissionManager, adminSettingsService,
                userManager, List.of(telegramSender));
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, regular)).thenReturn(false);
        when(adminSettingsService.get(anyString(), anyString())).thenReturn("");
    }

    // --- GET ---

    @Test
    public void getReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.get().getStatus());
    }

    @Test
    public void getReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.get().getStatus());
    }

    @Test
    public void getReturnsAllKnownKeysForAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.get();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals(AdminSettingsResource.KNOWN_KEYS.size(), body.size());
        for (String key : AdminSettingsResource.KNOWN_KEYS) {
            assertTrue("Ключ отсутствует в ответе: " + key, body.containsKey(key));
        }
    }

    @Test
    public void getReturnsEmptyStringForSecretRegardlessOfStoredValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_TOKEN, "")).thenReturn("real-token");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        // секрет никогда не возвращается — write-only
        assertEquals("", body.get(ChannelKeys.MATTERMOST_TOKEN));
    }

    @Test
    public void getReturnsIsTrueWhenSecretIsSet() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_TOKEN, "")).thenReturn("real-token");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        assertEquals("true", body.get(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
    }

    @Test
    public void getReturnsTrueForBothIsSetKeysWhenBothSecretsAreSet() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_TOKEN, "")).thenReturn("mm-token");
        when(adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("tg-token");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        assertEquals("true", body.get(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
        assertEquals("true", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
        assertEquals("", body.get(ChannelKeys.MATTERMOST_TOKEN));
        assertEquals("", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN));
    }

    @Test
    public void getReturnsIsFalseWhenSecretIsNotSet() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        assertEquals("false", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN + AdminSettingsResource.IS_SET_SUFFIX));
        assertEquals("", body.get(ChannelKeys.TELEGRAM_BOT_TOKEN));
    }

    @Test
    public void getReturnsBotIdAsPlainText() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.MATTERMOST_BOT_ID, "")).thenReturn("bot-abc-123");

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resource.get().getEntity();

        // botId — публичный идентификатор, не секрет
        assertEquals("bot-abc-123", body.get(ChannelKeys.MATTERMOST_BOT_ID));
    }

    // --- PUT ---

    @Test
    public void putReturns401WhenNotLoggedIn() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        assertEquals(401, resource.set(Map.of("email.enabled", "true")).getStatus());
    }

    @Test
    public void putReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.set(Map.of("email.enabled", "true")).getStatus());
    }

    @Test
    public void putReturns400WhenBodyIsEmpty() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.set(null).getStatus());
        assertEquals(400, resource.set(Map.of()).getStatus());
    }

    @Test
    public void putSavesKnownKeysAndReturns204() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.set(Map.of(
                "email.enabled", "false",
                ChannelKeys.MATTERMOST_TOKEN, "secret123"
        ));

        assertEquals(204, response.getStatus());
        verify(adminSettingsService).set("email.enabled", "false");
        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_TOKEN, "secret123");
    }

    @Test
    public void putReturns400ForInvalidBooleanValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(400, resource.set(Map.of("email.enabled", "yes")).getStatus());
        assertEquals(400, resource.set(Map.of("mattermost.enabled", "1")).getStatus());
        // пустая строка — не ошибка, а «не задано»: см. putIgnoresBlankBooleanInsteadOfRejecting
    }

    @Test
    public void putAcceptsValidBooleanValues() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(204, resource.set(Map.of(ActionTemplates.WATCHERS_ENABLED_KEY, "true")).getStatus());
        assertEquals(204, resource.set(Map.of("mattermost.enabled", "false")).getStatus());
    }

    /** Включить канал без проверки нельзя — иначе настройку сохраняют «на глаз». */
    @Test
    public void putRejectsEnablingChannelWithoutTest() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.set(Map.of("telegram.enabled", "true"));

        assertEquals(400, response.getStatus());
        verify(adminSettingsService, never()).set(eq("telegram.enabled"), anyString());
    }

    /** Проверка прошла с этими же настройками — включаем. */
    @Test
    public void putEnablesChannelAfterSuccessfulTest() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("123:ABC");
        rememberTest();

        assertEquals(204, resource.set(Map.of("telegram.enabled", "true")).getStatus());
        verify(adminSettingsService).set("telegram.enabled", "true");
    }

    /**
     * Проверили один токен, а включают канал с другим в том же запросе — отпечаток
     * не сойдётся. Иначе запрет обходился бы одним PUT.
     */
    @Test
    public void putRejectsEnablingWithConfigThatWasNotTested() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ChannelKeys.TELEGRAM_BOT_TOKEN, "")).thenReturn("123:ABC");
        rememberTest();

        Response response = resource.set(Map.of(
                "telegram.enabled", "true",
                ChannelKeys.TELEGRAM_BOT_TOKEN, "другой:токен"));

        assertEquals(400, response.getStatus());
        verify(adminSettingsService, never()).set(eq("telegram.enabled"), anyString());
    }

    /** Уже включённый канал проверки не требует: правка домена не должна её просить. */
    @Test
    public void putAllowsEditingAlreadyEnabledChannel() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get("mattermost.enabled", "false")).thenReturn("true");

        Response response = resource.set(Map.of(
                "mattermost.enabled", "true",
                ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com"));

        assertEquals(204, response.getStatus());
        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com");
    }

    /**
     * null дошёл бы до БД, а потом cache.put(key, null) ронял бы каждый get
     * этого ключа — рассылка молча ломалась бы до перезапуска.
     */
    @Test
    public void putReturns400ForNullValue() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put(ChannelKeys.MATTERMOST_DOMAIN, null);

        assertEquals(400, resource.set(body).getStatus());
        verify(adminSettingsService, never()).set(anyString(), any());
    }

    @Test
    public void putIgnoresUnknownKeys() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("unknown.key", "value", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq("unknown.key"), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    @Test
    public void putDoesNotOverwriteSecretWithBlankValue() {
        // пустое значение секрета = "не менять существующий" (write-only семантика)
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_TOKEN, "", "email.enabled", "false"));

        verify(adminSettingsService, never()).set(eq(ChannelKeys.MATTERMOST_TOKEN), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    @Test
    public void putSavesSecretWhenNewValueProvided() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123456:ABC-DEF"));

        verify(adminSettingsService).set(ChannelKeys.TELEGRAM_BOT_TOKEN, "123456:ABC-DEF");
    }

    @Test
    public void putIgnoresIsSetKeys() {
        // .isSet ключи read-only — PUT должен их игнорировать
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX, "true"));

        verify(adminSettingsService, never()).set(anyString(), anyString());
    }

    @Test
    public void putIgnoresIsSetKeyButSavesOtherKeysInSameRequest() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(
                ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX, "true",
                "email.enabled", "false"
        ));

        verify(adminSettingsService, never()).set(eq(ChannelKeys.MATTERMOST_TOKEN + AdminSettingsResource.IS_SET_SUFFIX), any());
        verify(adminSettingsService).set("email.enabled", "false");
    }

    @Test
    public void putAllowsClearingNonSecretFieldWithEmptyString() {
        // не-секретные поля (domain) очищать можно
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of(ChannelKeys.MATTERMOST_DOMAIN, ""));

        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_DOMAIN, "");
    }

    // --- шаблоны действий ---

    @Test
    public void rejectsTemplateWithUnknownPlaceholder() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        String key = ActionTemplates.templateKey(NotificationAction.MENTION, NotificationChannel.TELEGRAM);

        Response response = resource.set(Map.of(key, "{issuekey} в {summary}"));

        assertEquals(400, response.getStatus());
        verify(adminSettingsService, never()).set(anyString(), anyString());
    }

    @Test
    public void acceptsTemplateWithKnownPlaceholders() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        String key = ActionTemplates.templateKey(NotificationAction.MENTION, NotificationChannel.TELEGRAM);

        Response response = resource.set(Map.of(key, "{issueKey} — {summary}"));

        assertEquals(204, response.getStatus());
        verify(adminSettingsService).set(key, "{issueKey} — {summary}");
    }

    // --- «не задано» у булевых ключей ---

    /** Галка, которой нет в базе, должна приходить как "false": клиент вернёт это значение в PUT. */
    @Test
    public void getAsksFalseAsDefaultForBooleanKeys() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.get();

        verify(adminSettingsService).get("mattermost.enabled", "false");
        verify(adminSettingsService).get(ActionTemplates.WATCHERS_ENABLED_KEY, "false");
        verify(adminSettingsService).get(ChannelKeys.MATTERMOST_DOMAIN, "");
    }

    /** Страница, открытая до обновления плагина, шлёт пустую строку — это не ошибка. */
    @Test
    public void putIgnoresBlankBooleanInsteadOfRejecting() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        String enabledKey = "mattermost.enabled";

        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put(enabledKey, "");
        body.put(ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com");

        Response response = resource.set(body);

        assertEquals(204, response.getStatus());
        verify(adminSettingsService, never()).set(eq(enabledKey), anyString());
        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com");
    }

    /** Кривой домен ломает URI.create в клиенте — ловим на сохранении, а не в логе отправки. */
    @Test
    public void putReturns400WhenMattermostDomainIsNotAbsoluteUrl() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.set(Map.of(ChannelKeys.MATTERMOST_DOMAIN, "mm.example.com")).getStatus());
        assertEquals(400, resource.set(Map.of(ChannelKeys.MATTERMOST_DOMAIN, "https://mm example.com")).getStatus());
    }

    @Test
    public void putAcceptsAbsoluteMattermostDomain() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(204, resource.set(Map.of(ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com")).getStatus());
        verify(adminSettingsService).set(ChannelKeys.MATTERMOST_DOMAIN, "https://mm.example.com");
    }

    // --- POST /test ---

    @Test
    public void testReturns403ForNonAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(regular);
        assertEquals(403, resource.test(request(TELEGRAM, Map.of())).getStatus());
        verify(telegramSender, never()).sendTest(any(), anyString(), anyMap());
    }

    @Test
    public void testReturns400ForUnknownChannel() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.test(request("ICQ", Map.of())).getStatus());
    }

    /** Канала без отправщика быть не должно, но 500 из-за него админ видеть не обязан. */
    @Test
    public void testReturns400WhenChannelHasNoSender() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.test(request("MATTERMOST", Map.of())).getStatus());
    }

    @Test
    public void testSendsMessageToRequestingAdmin() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.test(request("TELEGRAM",
                Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC")));

        assertEquals(200, response.getStatus());
        verify(telegramSender).sendTest(admin, TestMessages.forChannel(NotificationChannel.TELEGRAM, admin.getDisplayName()),
                Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"));
    }

    /**
     * Значения из формы не сохраняются: проверка идёт до записи настроек.
     * Пишется только отметка о том, какая конфигурация проверена — по ней PUT
     * решает, можно ли включать канал.
     */
    @Test
    public void testSavesOnlyTestedMarker() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.test(request(TELEGRAM, Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC")));

        verify(adminSettingsService, never()).set(eq(ChannelKeys.TELEGRAM_BOT_TOKEN), anyString());
        verify(adminSettingsService, never()).set(eq("telegram.enabled"), anyString());
        verify(adminSettingsService).set(
                eq(AdminSettingsResource.testedKey(NotificationChannel.TELEGRAM)), anyString());
    }

    /** Через проверку нельзя подсунуть отправщику произвольную настройку. */
    @Test
    public void testPassesOnlyKnownKeysToSender() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC");
        form.put("какой-то.мусор", "значение");
        form.put(ChannelKeys.TELEGRAM_BOT_USERNAME, "   ");

        resource.test(request(TELEGRAM, form));

        verify(telegramSender).sendTest(admin, TestMessages.forChannel(NotificationChannel.TELEGRAM, admin.getDisplayName()),
                Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"));
    }

    /** Проверка выбранному пользователю Jira: ищем по логину. */
    @Test
    public void testSendsToChosenJiraUser() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        MockApplicationUser target = new MockApplicationUser("jdoe");
        when(userManager.getUserByName("jdoe")).thenReturn(target);

        Response response = resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "user", "jdoe"));

        assertEquals(200, response.getStatus());
        verify(telegramSender).sendTest(eq(target), anyString(), anyMap());
    }

    /** Логина нет — ищем по ключу: админ копирует то, что видит в профиле. */
    @Test
    public void testFindsJiraUserByKeyWhenNameDoesNotMatch() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        MockApplicationUser target = new MockApplicationUser("jdoe");
        when(userManager.getUserByName("JIRAUSER10100")).thenReturn(null);
        when(userManager.getUserByKey("JIRAUSER10100")).thenReturn(target);

        assertEquals(200, resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "user", "JIRAUSER10100")).getStatus());
        verify(telegramSender).sendTest(eq(target), anyString(), anyMap());
    }

    @Test
    public void testReturns400WhenJiraUserNotFound() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "user", "нет-такого"));

        assertEquals(400, response.getStatus());
        verify(telegramSender, never()).sendTest(any(), anyString(), anyMap());
    }

    /** Проверка на произвольный адрес идёт мимо пользователей Jira. */
    @Test
    public void testSendsToPlainEmailAddress() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "email", "qa@example.com"));

        assertEquals(200, response.getStatus());
        verify(telegramSender).sendTestTo(eq("qa@example.com"), anyString(), anyMap());
    }

    @Test
    public void testReturns400ForMalformedEmail() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        Response response = resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "email", "куда-то"));

        assertEquals(400, response.getStatus());
        verify(telegramSender, never()).sendTestTo(anyString(), anyString(), anyMap());
    }

    /** Канал, который не умеет отправку на адрес, отвечает понятным текстом, а не 500. */
    @Test
    public void testReturns400WhenChannelCannotSendToAddress() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        doThrow(new UnsupportedOperationException("Канал TELEGRAM умеет проверку только на пользователя Jira"))
                .when(telegramSender).sendTestTo(anyString(), anyString(), anyMap());

        Response response = resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "email", "qa@example.com"));

        assertEquals(400, response.getStatus());
        assertEquals("Канал TELEGRAM умеет проверку только на пользователя Jira",
                ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    public void testReturns400ForUnknownRecipientType() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(400, resource.test(request(Map.of(ChannelKeys.TELEGRAM_BOT_TOKEN, "123:ABC"), "всем", "")).getStatus());
    }

    /** Письмо проверки отличается от уведомления темой и телом. */
    @Test
    public void emailTestUsesItsOwnLetter() {
        String letter = TestMessages.forChannel(NotificationChannel.EMAIL, "Иван");

        assertTrue(letter.contains("Иван"));
        assertTrue(letter.contains("проверочное письмо"));
        // каркас как у настоящих писем плагина — см. EmailMessageFormatter
        assertTrue(letter.startsWith("<html><body"));
        assertTrue(letter.endsWith("</body></html>"));
    }

    /** Разметка проверки — своя на канал: Markdown, HTML и письмо не взаимозаменяемы. */
    @Test
    public void testMessageMarkupMatchesChannel() {
        assertTrue(TestMessages.forChannel(NotificationChannel.MATTERMOST, "Иван")
                .startsWith("**Issue Notifier"));
        assertTrue(TestMessages.forChannel(NotificationChannel.TELEGRAM, "Иван")
                .startsWith("<b>Issue Notifier"));
        assertTrue(TestMessages.forChannel(NotificationChannel.EMAIL, "Иван")
                .startsWith("<html><body"));
    }

    /** Угловая скобка в имени сломала бы разбор HTML в Telegram. */
    @Test
    public void telegramTestEscapesInitiatorName() {
        assertTrue(TestMessages.forChannel(NotificationChannel.TELEGRAM, "<Иван>")
                .contains("&lt;Иван&gt;"));
        // в Mattermost экранирования нет: обратные слэши получатель бы увидел
        assertTrue(TestMessages.forChannel(NotificationChannel.MATTERMOST, "<Иван>")
                .contains("<Иван>"));
    }

    /** Ошибку канала показываем администратору текстом, а не 500-й страницей. */
    @Test
    public void testReturns400WithChannelErrorMessage() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        doThrow(new IllegalStateException("Токен не подошёл"))
                .when(telegramSender).sendTest(any(), anyString(), anyMap());

        Response response = resource.test(request(TELEGRAM, Map.of()));

        assertEquals(400, response.getStatus());
        assertEquals("Токен не подошёл", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    // --- рассылка наблюдателям ---

    /**
     * Записи нет — админка должна показать снятую галку, а не унаследованное «включено».
     * Проверяется дефолт, с которым ресурс спрашивает настройку: сам ответ в этом тесте
     * приходит от мока, а не от сервиса (см. общий стаб в setUp).
     */
    @Test
    public void getAsksWatchersSettingWithDisabledDefault() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.get();

        verify(adminSettingsService).get(ActionTemplates.WATCHERS_ENABLED_KEY, "false");
    }

    @Test
    public void putSavesWatchersEnabled() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        assertEquals(204, resource.set(Map.of(ActionTemplates.WATCHERS_ENABLED_KEY, "true")).getStatus());
        verify(adminSettingsService).set(ActionTemplates.WATCHERS_ENABLED_KEY, "true");
    }

    /** Старый инвертированный ключ больше не известен — запись по нему не проходит. */
    @Test
    public void putIgnoresLegacyWatchersDisabledKey() {
        when(authContext.getLoggedInUser()).thenReturn(admin);

        resource.set(Map.of("watchers.disabled", "true"));

        verify(adminSettingsService, never()).set(eq("watchers.disabled"), any());
    }

    // --- режим текста комментария ---

    @Test
    public void putRejectsUnknownCommentTextMode() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(400, resource.set(Map.of(CommentTextMode.KEY, "иногда")).getStatus());
    }

    @Test
    public void putAcceptsKnownCommentTextMode() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        assertEquals(204, resource.set(Map.of(CommentTextMode.KEY, "shown")).getStatus());
        verify(adminSettingsService).set(CommentTextMode.KEY, "shown");
    }

    /** Записи о режиме ещё нет — GET отдаёт режим, выведенный из старой галки. */
    @Test
    public void getFallsBackToLegacyCommentTextFlag() {
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(adminSettingsService.get(ActionTemplates.HIDE_COMMENT_TEXT_KEY, "false")).thenReturn("true");
        when(adminSettingsService.get(eq(CommentTextMode.KEY), anyString()))
                .thenAnswer(call -> call.getArgument(1));

        @SuppressWarnings("unchecked")
        Map<String, String> settings = (Map<String, String>) resource.get().getEntity();

        assertEquals(CommentTextMode.HIDDEN.key(), settings.get(CommentTextMode.KEY));
    }

    /**
     * Проверка канала прошла с текущими сохранёнными настройками: отметку считает
     * сам ресурс, поэтому берём её из его же ответа на проверку.
     */
    private void rememberTest() {
        String key = AdminSettingsResource.testedKey(NotificationChannel.TELEGRAM);
        resource.test(request(TELEGRAM, Map.of()));
        ArgumentCaptor<String> marker = ArgumentCaptor.forClass(String.class);
        verify(adminSettingsService).set(eq(key), marker.capture());
        when(adminSettingsService.get(key, "")).thenReturn(marker.getValue());
    }

    /** Проверка с выбранным получателем — во всех таких тестах канал один. */
    private static ChannelTestDto request(Map<String, String> settings,
                                         String recipientType, String recipient) {
        ChannelTestDto dto = request(TELEGRAM, settings);
        dto.setRecipientType(recipientType);
        dto.setRecipient(recipient);
        return dto;
    }

    private static ChannelTestDto request(String channel, Map<String, String> settings) {
        ChannelTestDto dto = new ChannelTestDto();
        dto.setChannel(channel);
        dto.setSettings(settings);
        return dto;
    }
}
