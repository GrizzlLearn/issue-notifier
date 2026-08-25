package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;

/** DTO для чтения и записи делегации. {@code activeUntil} — ISO-дата "YYYY-MM-DD" или null (бессрочно). */
public class DelegationDto {

    // @JsonProperty обязателен — Jira отключает автодетект get-геттеров в своём Jackson,
    // без аннотации поле молча пропадает из JSON (виден только is-геттер enabled)
    @JsonProperty
    private String toUserKey;
    @JsonProperty
    private String activeUntil;

    public DelegationDto() {}

    public DelegationDto(String toUserKey, String activeUntil) {
        this.toUserKey = toUserKey;
        this.activeUntil = activeUntil;
    }

    public String getToUserKey() { return toUserKey; }
    public void setToUserKey(String toUserKey) { this.toUserKey = toUserKey; }

    public String getActiveUntil() { return activeUntil; }
    public void setActiveUntil(String activeUntil) { this.activeUntil = activeUntil; }
}
