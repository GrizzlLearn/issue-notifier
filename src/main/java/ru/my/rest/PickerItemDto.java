package ru.my.rest;

import org.codehaus.jackson.annotate.JsonProperty;

/** Элемент выпадающего списка пикера (проект или пользователь): значение + отображаемый текст. */
public class PickerItemDto {

    // @JsonProperty обязателен — Jira отключает автодетект get-геттеров в своём Jackson,
    // без аннотации поле молча пропадает из JSON (виден только is-геттер enabled)
    @JsonProperty
    private String value;
    @JsonProperty
    private String label;

    public PickerItemDto() {}

    public PickerItemDto(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
