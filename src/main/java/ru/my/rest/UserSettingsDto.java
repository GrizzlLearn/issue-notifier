package ru.my.rest;

import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UserSettingsDto {

    private boolean enabled;
    private List<String> projects;
    private List<String> channels;
    private String telegramChatId;

    public UserSettingsDto() {}

    public UserSettingsDto(boolean enabled, List<String> projects, List<String> channels, String telegramChatId) {
        this.enabled = enabled;
        this.projects = projects != null ? List.copyOf(projects) : List.of("*");
        this.channels = channels != null ? List.copyOf(channels) : List.of();
        this.telegramChatId = telegramChatId;
    }

    public static UserSettingsDto from(UserSettings settings) {
        return new UserSettingsDto(
                settings.isEnabled(),
                settings.getProjects(),
                settings.getChannels().stream().map(NotificationChannel::name).collect(Collectors.toList()),
                settings.getTelegramChatId());
    }

    public UserSettings toModel() {
        List<String> src = channels != null ? channels : List.of();
        List<NotificationChannel> parsedChannels = src.stream()
                .flatMap(name -> {
                    try {
                        return Stream.of(NotificationChannel.valueOf(name));
                    } catch (IllegalArgumentException e) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());

        return UserSettings.builder()
                .enabled(enabled)
                .projects(projects != null ? projects : List.of("*"))
                .channels(parsedChannels)
                .telegramChatId(telegramChatId)
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getProjects() { return projects; }
    public void setProjects(List<String> projects) { this.projects = projects; }

    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
}
