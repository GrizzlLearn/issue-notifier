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

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.Optional;

/**
 * Реализация {@link DelegationService} на базе Active Objects.
 * <p>
 * Для разрешения ключа делегата в {@link ApplicationUser} используется
 * Jira {@link UserManager}. Если делегат был удалён из системы —
 * {@link #getEffectiveRecipient} прозрачно возвращает оригинального пользователя.
 */
@Named
@ExportAsService(DelegationService.class)
public class DelegationServiceImpl implements DelegationService {

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
    public ApplicationUser getEffectiveRecipient(ApplicationUser user) {
        return getDelegation(user)
                .filter(DelegationInfo::isActive)
                .map(d -> userManager.getUserByKey(d.getToUserKey()))
                .orElse(user);
    }

    @Override
    public void setDelegation(ApplicationUser from, ApplicationUser to, @Nullable Date activeUntil) {
        if (from.getKey().equals(to.getKey())) {
            throw new IllegalArgumentException(
                    "Нельзя делегировать уведомления самому себе: " + from.getDisplayName());
        }
        ao.executeInTransaction(() -> {
            NotificationDelegationEntity[] rows = ao.find(
                    NotificationDelegationEntity.class,
                    Query.select().where("FROM_USER_KEY = ?", from.getKey()));

            NotificationDelegationEntity entity = rows.length > 0
                    ? rows[0]
                    : ao.create(NotificationDelegationEntity.class,
                            new DBParam("FROM_USER_KEY", from.getKey()));

            entity.setToUserKey(to.getKey());
            entity.setActiveUntil(activeUntil);
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
        NotificationDelegationEntity[] rows = ao.find(
                NotificationDelegationEntity.class,
                Query.select().where("FROM_USER_KEY = ?", from.getKey()));

        if (rows.length == 0) {
            return Optional.empty();
        }
        NotificationDelegationEntity entity = rows[0];
        return Optional.of(new DelegationInfo(entity.getToUserKey(), entity.getActiveUntil()));
    }
}
