package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;

import java.util.List;

/** DTO для чтения и записи делегации. {@code activeUntil} — ISO-дата "YYYY-MM-DD" или null (бессрочно). */
public class DelegationDto {

    // @JsonProperty обязателен — Jira отключает автодетект get-геттеров в своём Jackson,
    // без аннотации поле молча пропадает из JSON (виден только is-геттер enabled)
    @JsonProperty
    private List<String> toUserKeys;
    @JsonProperty
    private String activeUntil;

    public DelegationDto() {}

    public DelegationDto(List<String> toUserKeys, String activeUntil) {
        this.toUserKeys = toUserKeys != null ? List.copyOf(toUserKeys) : List.of();
        this.activeUntil = activeUntil;
    }

    public List<String> getToUserKeys() { return toUserKeys; }
    public void setToUserKeys(List<String> toUserKeys) { this.toUserKeys = toUserKeys; }

    public String getActiveUntil() { return activeUntil; }
    public void setActiveUntil(String activeUntil) { this.activeUntil = activeUntil; }
}
