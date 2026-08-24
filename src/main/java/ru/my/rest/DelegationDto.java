package ru.my.rest;

/** DTO для чтения и записи делегации. {@code activeUntil} — ISO-дата "YYYY-MM-DD" или null (бессрочно). */
public class DelegationDto {

    private String toUserKey;
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
