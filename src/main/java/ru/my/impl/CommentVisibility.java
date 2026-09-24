package ru.my.impl;

import com.atlassian.jira.entity.property.EntityProperty;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.comments.Comment;

/**
 * Кому можно рассказать о комментарии.
 * <p>
 * Различаем два вида ограничений:
 * <ul>
 *   <li>внутренний комментарий Service Desk — заказчик его не видит, поэтому
 *       уведомление уходит только исполнителю;</li>
 *   <li>ограничение по группе или роли проекта — уведомление уходит всем
 *       получателям, но без текста.</li>
 * </ul>
 */
public final class CommentVisibility {

    /** Свойство комментария, которым Jira Service Desk помечает внутренние комментарии. */
    private static final String SD_PROPERTY_ENTITY = "CommentProperty";
    private static final String SD_PROPERTY_KEY = "sd.public.comment";

    private CommentVisibility() {
    }

    /** Ограничен ли комментарий группой или ролью проекта — читается без обращения к БД. */
    public static boolean isRestrictedByLevel(Comment comment) {
        return comment.getGroupLevel() != null || comment.getRoleLevelId() != null;
    }

    /**
     * Внутренний ли комментарий Service Desk. Комментарий без свойства считается
     * внешним: так его показывает и сама Jira.
     * <p>
     * ponytail: значение свойства разбираем поиском подстроки — это
     * {@code {"internal":true}} и ничего больше; полноценный парсер понадобится,
     * если Atlassian начнёт класть туда что-то ещё.
     *
     * @param commentId id комментария; {@code null} — считаем внешним
     */
    public static boolean isServiceDeskInternal(Long commentId, JsonEntityPropertyManager properties) {
        if (commentId == null) {
            return false;
        }
        EntityProperty property = properties.get(SD_PROPERTY_ENTITY, commentId, SD_PROPERTY_KEY);
        if (property == null || property.getValue() == null) {
            return false;
        }
        return property.getValue().replace(" ", "").contains("\"internal\":true");
    }
}
