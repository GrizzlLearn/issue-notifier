package ru.my.model;

import org.codehaus.jackson.map.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Снимок события для восстановления рассылки после остановки процесса Jira. */
public final class QueuedIssueEvent {
    private final Long issueId;
    private final String authorKey;
    private final Long typeId;
    private final boolean hasComment;
    private final Long commentId;
    private final String commentBody;
    private final boolean restrictedByLevel;
    private final DiffResult diff;

    public QueuedIssueEvent(Long issueId, String authorKey, Long typeId, boolean hasComment, Long commentId, String commentBody, boolean restrictedByLevel, DiffResult diff) {
        this.issueId = issueId;
        this.authorKey = authorKey;
        this.typeId = typeId;
        this.hasComment = hasComment;
        this.commentId = commentId;
        this.commentBody = commentBody;
        this.restrictedByLevel = restrictedByLevel;
        this.diff = diff;
    }

    public Long issueId() { return issueId; }
    public String authorKey() { return authorKey; }
    public Long typeId() { return typeId; }
    public boolean hasComment() { return hasComment; }
    public Long commentId() { return commentId; }
    public String commentBody() { return commentBody; }
    public boolean restrictedByLevel() { return restrictedByLevel; }
    public DiffResult diff() { return diff; }

    private static final ObjectMapper JSON = new ObjectMapper();

    public String encode() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issueId", issueId);
        data.put("authorKey", authorKey);
        data.put("typeId", typeId);
        data.put("hasComment", hasComment);
        data.put("commentId", commentId);
        data.put("commentBody", commentBody);
        data.put("restrictedByLevel", restrictedByLevel);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (DiffResult.FieldChange change : diff.getChanges()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fieldName", change.fieldName());
            row.put("fromValue", change.fromValue());
            row.put("toValue", change.toValue());
            row.put("toId", change.toId());
            row.put("custom", change.isCustom());
            changes.add(row);
        }
        data.put("changes", changes);
        try {
            return JSON.writeValueAsString(data);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать событие уведомления", e);
        }
    }

    public static QueuedIssueEvent decode(String payload) {
        try {
            Object parsed = JSON.readValue(payload, Object.class);
            if (!(parsed instanceof Map<?, ?> data) || !(data.get("changes") instanceof List<?> rows)) {
                throw new IllegalArgumentException("Некорректное событие уведомления");
            }
            List<DiffResult.FieldChange> changes = new ArrayList<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> change)) throw new IllegalArgumentException("Некорректное изменение");
                changes.add(new DiffResult.FieldChange((String) change.get("fieldName"),
                        (String) change.get("fromValue"), (String) change.get("toValue"),
                        (String) change.get("toId"), Boolean.TRUE.equals(change.get("custom"))));
            }
            return new QueuedIssueEvent(number(data.get("issueId")), (String) data.get("authorKey"),
                    number(data.get("typeId")), Boolean.TRUE.equals(data.get("hasComment")),
                    number(data.get("commentId")), (String) data.get("commentBody"),
                    Boolean.TRUE.equals(data.get("restrictedByLevel")), new DiffResult(changes));
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать событие уведомления", e);
        }
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
