package ru.my.model;

/** Нечитаемые сохранённые данные: автоматический повтор не исправит формат. */
public class CorruptQueuedDataException extends IllegalArgumentException {
    public CorruptQueuedDataException(String message, Throwable cause) { super(message, cause); }
    public CorruptQueuedDataException() { super("Не удалось прочитать сохранённые данные события"); }
}
