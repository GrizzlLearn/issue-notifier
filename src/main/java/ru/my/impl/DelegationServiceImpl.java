package ru.my.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.DBParam;
import net.java.ao.Query;
import ru.my.ao.NotificationDelegationEntity;
import ru.my.api.DelegationService;
import ru.my.model.DelegationInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Реализация {@link DelegationService} на базе Active Objects.
 * <p>
 * Для разрешения ключа делегата в {@link ApplicationUser} используется
 * Jira {@link UserManager}. Если делегат был удалён из системы —
 * {@link #getEffectiveRecipients} прозрачно исключает его из результата.
 * <p>
 * Делегирование однозвенное: метод {@link #getEffectiveRecipients} смотрит только
 * одну запись в таблице делегаций и не рекурсирует дальше. Это сознательное
 * ограничение — транзитивные цепочки создают риск циклов и непредсказуемого
 * поведения при одновременном отпуске нескольких сотрудников.
 * <p>
 * Схема AO не меняется ради поддержки нескольких получателей: колонка
 * {@code TO_USER_KEY} хранит ключи через запятую. Это сознательный компромисс —
 * менять схему (например, снимать {@code @Unique} с {@code FROM_USER_KEY} и заводить
 * по строке на получателя) рискованно на живых базах, а для маленького списка
 * получателей на пользователя CSV полностью достаточен.
 */
@Named
@ExportAsService(DelegationService.class)
public class DelegationServiceImpl implements DelegationService {

    private static final Logger log = LoggerFactory.getLogger(DelegationServiceImpl.class);
    private static final String KEY_SEPARATOR = ",";

    private final ActiveObjects ao;
    private final UserManager userManager;

    @Inject
    public DelegationServiceImpl(
            @ComponentImport ActiveObjects ao,
            @ComponentImport UserManager userManager) {
        this.ao = ao;
        this.userManager = userManager;
    }

    @Override
    public List<ApplicationUser> getEffectiveRecipients(ApplicationUser user) {
        List<ApplicationUser> recipients = getDelegation(user)
                .filter(DelegationInfo::isActive)
                .map(d -> resolveDelegates(d.getToUserKeys()))
                .orElse(List.of());
        return recipients.isEmpty() ? List.of(user) : recipients;
    }

    private List<ApplicationUser> resolveDelegates(List<String> keys) {
        List<ApplicationUser> resolved = new ArrayList<>();
        for (String key : keys) {
            try {
                ApplicationUser delegate = userManager.getUserByKey(key);
                if (delegate != null) {
                    resolved.add(delegate);
                } else {
                    log.warn("Делегат '{}' не найден, пропускаем", key);
                }
            } catch (Exception e) {
                log.warn("Не удалось получить делегата '{}', пропускаем", key, e);
            }
        }
        return resolved;
    }

    @Override
    public void setDelegation(ApplicationUser from, List<ApplicationUser> to, @Nullable Instant activeUntil) {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Список получателей не может быть пустым");
        }
        for (ApplicationUser delegate : to) {
            if (from.getKey().equals(delegate.getKey())) {
                throw new IllegalArgumentException(
                        "Нельзя делегировать уведомления самому себе: " + from.getDisplayName());
            }
        }
        String toUserKeysCsv = to.stream()
                .map(ApplicationUser::getKey)
                .distinct()
                .collect(Collectors.joining(KEY_SEPARATOR));
        // AO работает с java.util.Date — конвертируем на границе слоя
        Date dateUntil = activeUntil != null ? Date.from(activeUntil) : null;
        ao.executeInTransaction(() -> {
            NotificationDelegationEntity[] rows = ao.find(
                    NotificationDelegationEntity.class,
                    Query.select().where("FROM_USER_KEY = ?", from.getKey()));

            NotificationDelegationEntity entity = rows.length > 0
                    ? rows[0]
                    : ao.create(NotificationDelegationEntity.class,
                            new DBParam("FROM_USER_KEY", from.getKey()));

            entity.setToUserKey(toUserKeysCsv);
            entity.setActiveUntil(dateUntil);
            entity.save();
            return null;
        });
    }

    @Override
    public void removeDelegation(ApplicationUser from) {
        ao.executeInTransaction(() -> {
            NotificationDelegationEntity[] rows = ao.find(
                    NotificationDelegationEntity.class,
                    Query.select().where("FROM_USER_KEY = ?", from.getKey()));
            for (NotificationDelegationEntity row : rows) {
                ao.delete(row);
            }
            return null;
        });
    }

    @Override
    public Optional<DelegationInfo> getDelegation(ApplicationUser from) {
        NotificationDelegationEntity[] rows;
        try {
            rows = ao.find(NotificationDelegationEntity.class,
                    Query.select().where("FROM_USER_KEY = ?", from.getKey()));
        } catch (IllegalStateException e) {
            log.warn("AO ещё не инициализирован, getDelegation возвращает пустой результат");
            return Optional.empty();
        }

        if (rows.length == 0) {
            return Optional.empty();
        }
        NotificationDelegationEntity entity = rows[0];
        // AO работает с java.util.Date — конвертируем на границе слоя
        Date rawDate = entity.getActiveUntil();
        Instant until = rawDate != null ? rawDate.toInstant() : null;
        return Optional.of(new DelegationInfo(parseKeys(entity.getToUserKey()), until));
    }

    private static List<String> parseKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(KEY_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
