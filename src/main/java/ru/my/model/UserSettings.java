package ru.my.model;

import java.util.List;

/**
 * Настройки уведомлений конкретного пользователя.
 * Иммутабельный value object; создаётся через {@link Builder}.
 */
public class UserSettings {

    private final boolean enabled;
    private final List<String> projects;
    private final List<NotificationChannel> channels;
    private final String telegramChatId;

    private UserSettings(Builder builder) {
        this.enabled = builder.enabled;
        this.projects = List.copyOf(builder.projects);
        this.channels = List.copyOf(builder.channels);
        this.telegramChatId = builder.telegramChatId;
    }

    /**
     * Дефолтные настройки для нового пользователя: уведомления включены,
     * все проекты, каналы не выбраны (пользователь должен настроить вручную).
     */
    public static UserSettings defaultSettings() {
        return builder()
                .enabled(true)
                .projects(List.of("*"))
                .channels(List.of())
                .build();
    }

    /** Создаёт новый {@link Builder} с дефолтными значениями. */
    public static Builder builder() {
        return new Builder();
    }

    /** {@code true} — уведомления для пользователя включены. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ключи проектов, по которым пользователь хочет получать уведомления.
     * Специальное значение {@code "*"} означает все проекты.
     */
    public List<String> getProjects() {
        return projects;
    }

    /** Каналы доставки, которые пользователь включил. */
    public List<NotificationChannel> getChannels() {
        return channels;
    }

    /**
     * Идентификатор чата пользователя в Telegram.
     * {@code null} — Telegram не настроен.
     */
    public String getTelegramChatId() {
        return telegramChatId;
    }

    /** Билдер {@link UserSettings}. */
    public static class Builder {

        private boolean enabled = true;
        private List<String> projects = List.of("*");
        private List<NotificationChannel> channels = List.of();
        private String telegramChatId;

        /** @see UserSettings#isEnabled() */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @see UserSettings#getProjects() */
        public Builder projects(List<String> projects) {
            this.projects = projects;
            return this;
        }

        /** @see UserSettings#getChannels() */
        public Builder channels(List<NotificationChannel> channels) {
            this.channels = channels;
            return this;
        }

        /** @see UserSettings#getTelegramChatId() */
        public Builder telegramChatId(String telegramChatId) {
            this.telegramChatId = telegramChatId;
            return this;
        }

        /** @return новый иммутабельный {@link UserSettings} */
        public UserSettings build() {
            return new UserSettings(this);
        }
    }
}
