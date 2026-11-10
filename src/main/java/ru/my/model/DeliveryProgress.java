package ru.my.model;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Подтвержденные доставки одного события: чекпойнт записывается до изменения локального состояния. */
public final class DeliveryProgress {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Set<String> delivered = Set.of();
    private final Consumer<String> persist;

    public DeliveryProgress() { this(null, snapshot -> { }); }

    public DeliveryProgress(String raw, Consumer<String> persist) {
        this.persist = java.util.Objects.requireNonNull(persist, "persist");
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode data = JSON.readTree(raw);
            if (data == null || !data.isArray()) throw new IllegalArgumentException("Некорректный журнал доставок");
            Set<String> entries = new LinkedHashSet<>();
            for (JsonNode entry : data) {
                if (!entry.isTextual()) throw new IllegalArgumentException("Некорректная запись журнала доставок");
                JsonNode tuple = JSON.readTree(entry.getTextValue());
                if (tuple == null || !tuple.isArray() || tuple.size() != 3
                        || (!tuple.get(0).isNull() && !tuple.get(0).isTextual())
                        || !tuple.get(1).isTextual() || !tuple.get(2).isTextual()) {
                    throw new IllegalArgumentException("Некорректная запись журнала доставок");
                }
                if (!tuple.get(0).isNull()) NotificationAction.valueOf(tuple.get(0).getTextValue());
                NotificationChannel.valueOf(tuple.get(1).getTextValue());
                entries.add(entry.getTextValue());
            }
            delivered = Set.copyOf(entries);
        } catch (IOException | RuntimeException e) {
            throw new CorruptQueuedDataException("Не удалось прочитать журнал доставок", e);
        }
    }

    public boolean delivered(NotificationAction action, NotificationChannel channel, String recipientKey) {
        return delivered.contains(key(action, channel, recipientKey));
    }

    public void record(NotificationAction action, NotificationChannel channel, String recipientKey) {
        String key = key(action, channel, recipientKey);
        if (delivered.contains(key)) return;
        Set<String> next = new LinkedHashSet<>(delivered);
        next.add(key);
        // shortcut: полный JSON на каждый чекпойнт; при тысячах доставок на событие нужна отдельная таблица.
        try {
            persist.accept(JSON.writeValueAsString(next));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать журнал доставок");
        }
        delivered = Set.copyOf(next);
    }

    private static String key(NotificationAction action, NotificationChannel channel, String recipientKey) {
        java.util.Objects.requireNonNull(channel, "channel");
        java.util.Objects.requireNonNull(recipientKey, "recipientKey");
        try {
            return JSON.writeValueAsString(new String[]{action == null ? null : action.name(), channel.name(), recipientKey});
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось составить ключ доставки");
        }
    }
}
