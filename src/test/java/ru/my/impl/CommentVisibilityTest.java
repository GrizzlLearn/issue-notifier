package ru.my.impl;

import com.atlassian.jira.entity.property.EntityProperty;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.comments.Comment;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * От этого класса зависит, увидит ли заказчик Service Desk внутреннюю переписку
 * команды, поэтому проверяются все ветки — включая «свойства нет» и «internal:false».
 */
public class CommentVisibilityTest {

    private final JsonEntityPropertyManager properties = mock(JsonEntityPropertyManager.class);

    /**
     * Ограничений нет. Оба уровня задаются явно: у мока {@code getRoleLevelId()}
     * возвращает 0, а не null, и без этих стабов «комментарий без ограничений»
     * в тесте на самом деле был бы ограниченным.
     */
    @Test
    public void commentWithoutLevelsIsNotRestricted() {
        Comment comment = mock(Comment.class);
        when(comment.getGroupLevel()).thenReturn(null);
        when(comment.getRoleLevelId()).thenReturn(null);

        assertFalse(CommentVisibility.isRestrictedByLevel(comment));
    }

    @Test
    public void groupLevelRestrictsComment() {
        Comment comment = mock(Comment.class);
        when(comment.getGroupLevel()).thenReturn("jira-developers");
        when(comment.getRoleLevelId()).thenReturn(null);

        assertTrue(CommentVisibility.isRestrictedByLevel(comment));
    }

    @Test
    public void roleLevelRestrictsComment() {
        Comment comment = mock(Comment.class);
        when(comment.getRoleLevelId()).thenReturn(10100L);

        assertTrue(CommentVisibility.isRestrictedByLevel(comment));
    }

    /** Без id комментария в БД идти незачем: считаем комментарий внешним. */
    @Test
    public void nullCommentIdIsNotInternalAndDoesNotTouchDatabase() {
        assertFalse(CommentVisibility.isServiceDeskInternal(null, properties));

        verifyNoInteractions(properties);
    }

    @Test
    public void commentWithoutPropertyIsExternal() {
        when(properties.get("CommentProperty", 42L, "sd.public.comment")).thenReturn(null);

        assertFalse(CommentVisibility.isServiceDeskInternal(42L, properties));
        verify(properties).get("CommentProperty", 42L, "sd.public.comment");
    }

    @Test
    public void propertyWithoutValueIsExternal() {
        assertFalse(CommentVisibility.isServiceDeskInternal(42L, propertyValued(null)));
    }

    @Test
    public void internalTrueIsInternal() {
        assertTrue(CommentVisibility.isServiceDeskInternal(42L, propertyValued("{\"internal\":true}")));
    }

    /** Пробелы в JSON: Service Desk пишет свойство не в одном формате. */
    @Test
    public void internalTrueWithSpacesIsInternal() {
        assertTrue(CommentVisibility.isServiceDeskInternal(42L, propertyValued("{ \"internal\" : true }")));
    }

    @Test
    public void internalFalseIsExternal() {
        assertFalse(CommentVisibility.isServiceDeskInternal(42L, propertyValued("{\"internal\":false}")));
    }

    private static JsonEntityPropertyManager propertyValued(String value) {
        EntityProperty property = mock(EntityProperty.class);
        when(property.getValue()).thenReturn(value);
        JsonEntityPropertyManager properties = mock(JsonEntityPropertyManager.class);
        when(properties.get("CommentProperty", 42L, "sd.public.comment")).thenReturn(property);
        return properties;
    }
}
