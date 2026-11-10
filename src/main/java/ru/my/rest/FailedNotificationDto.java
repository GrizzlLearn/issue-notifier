package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;
import java.util.List;

/** Сведения о неудачной попытке без тела события и секретов; ошибка может содержать ключ получателя. */
public class FailedNotificationDto {
    @JsonProperty
    public int id;
    @JsonProperty
    public Long quarantinedAt;
    @JsonProperty
    public Long issueId;
    @JsonProperty
    public String issueKey;
    @JsonProperty
    public String issueUrl;
    @JsonProperty
    public String event;
    @JsonProperty
    public String context;
    @JsonProperty
    public String error;
    @JsonProperty
    public int attempts;
    @JsonProperty
    public Long lastFailedAt;
    @JsonProperty
    public Long nextAttemptAt;

    public static class Page {
        @JsonProperty
        public final List<FailedNotificationDto> items;
        @JsonProperty
        public final Integer nextBeforeId;

        public Page(List<FailedNotificationDto> items, Integer nextBeforeId) {
            this.items = items;
            this.nextBeforeId = nextBeforeId;
        }
    }
}
