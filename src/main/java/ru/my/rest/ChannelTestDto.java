package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;

import java.util.Map;

/**
 * Запрос проверочной отправки с админ-страницы.
 * <p>
 * {@link #getSettings()} — значения полей канала прямо из формы, ещё не сохранённые:
 * администратор должен убедиться, что введённые домен и токен рабочие, до того как
 * запишет их в настройки.
 */
@SuppressWarnings("unused") // сеттеры/геттеры используются JAX-RS при десериализации
public class ChannelTestDto {

    // @JsonProperty обязателен — Jira отключает автодетект геттеров в своём Jackson
    @JsonProperty
    private String channel;
    @JsonProperty
    private Map<String, String> settings;

    public ChannelTestDto() {}

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Map<String, String> getSettings() { return settings; }
    public void setSettings(Map<String, String> settings) { this.settings = settings; }
}
