package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;
import ru.my.model.CommentTextMode;
import ru.my.model.NotificationChannel;
import ru.my.model.UserSettings;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unused") // сеттеры/геттеры используются JAX-RS при сериализации
public class UserSettingsDto {

    // @JsonProperty обязателен — Jira отключает автодетект get-геттеров в своём Jackson,
    // без аннотации поле молча пропадает из JSON (виден только is-геттер enabled)
    @JsonProperty
    private boolean enabled;
    @JsonProperty
    private List<String> projects;
    @JsonProperty
    private List<String> channels;
    @JsonProperty
    private String telegramChatId;
    @JsonProperty
    private String telegramBotUsername; // read-only: из AdminSettings, не сохраняется
    @JsonProperty
    private List<String> enabledChannels; // read-only: каналы, включённые в админке
    @JsonProperty
    private boolean commentTextHidden;
    @JsonProperty
    private String commentTextMode; // read-only: режим текста комментария из админки
    @JsonProperty
    private boolean watchersEnabled; // read-only: включены ли в админке уведомления наблюдателям

    public UserSettingsDto() {}

    public UserSettingsDto(boolean enabled, List<String> projects, List<String> channels,
                           String telegramChatId, String telegramBotUsername) {
        this.enabled = enabled;
        this.projects = projects != null ? List.copyOf(projects) : List.of("*");
        this.channels = channels != null ? List.copyOf(channels) : List.of();
        this.telegramChatId = telegramChatId;
        this.telegramBotUsername = telegramBotUsername;
    }

    public static UserSettingsDto from(UserSettings settings, String telegramBotUsername,
                                       List<String> enabledChannels, CommentTextMode commentTextMode,
                                       boolean watchersEnabled) {
        UserSettingsDto dto = new UserSettingsDto(
                settings.isEnabled(),
                settings.getProjects(),
                settings.getChannels().stream().map(NotificationChannel::name).collect(Collectors.toList()),
                settings.getTelegramChatId(),
                telegramBotUsername);
        dto.enabledChannels = enabledChannels;
        dto.commentTextHidden = settings.isCommentTextHidden();
        dto.commentTextMode = commentTextMode.key();
        dto.watchersEnabled = watchersEnabled;
        return dto;
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
                .commentTextHidden(commentTextHidden)
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

    public String getTelegramBotUsername() { return telegramBotUsername; }
    public void setTelegramBotUsername(String telegramBotUsername) { this.telegramBotUsername = telegramBotUsername; }

    public List<String> getEnabledChannels() { return enabledChannels; }
    public void setEnabledChannels(List<String> enabledChannels) { this.enabledChannels = enabledChannels; }

    public boolean isCommentTextHidden() { return commentTextHidden; }
    public void setCommentTextHidden(boolean commentTextHidden) { this.commentTextHidden = commentTextHidden; }

    public String getCommentTextMode() { return commentTextMode; }
    public void setCommentTextMode(String commentTextMode) { this.commentTextMode = commentTextMode; }

    public boolean isWatchersEnabled() { return watchersEnabled; }
    public void setWatchersEnabled(boolean watchersEnabled) { this.watchersEnabled = watchersEnabled; }
}
