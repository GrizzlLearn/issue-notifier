package ru.my.model;

/**
 * Ограничения комментария, из-за которого рассылается уведомление.
 * <p>
 * Раньше решение «кому можно рассказать» принималось один раз на всё событие:
 * текст вырезался у всех получателей сразу, а сам список получателей проверялся
 * только на право видеть проект. Ограничение комментария по группе или роли
 * проверяется на каждом получателе, поэтому его нужно донести до рассылки —
 * этот объект и есть тот донос.
 *
 * @see ru.my.impl.CommentVisibility
 */
public final class CommentScope {

    /** Событие не про комментарий — ограничений нет. */
    public static final CommentScope NONE = new CommentScope(null, false);

    private final Long commentId;
    private final boolean serviceDeskInternal;

    public CommentScope(Long commentId, boolean serviceDeskInternal) {
        this.commentId = commentId;
        this.serviceDeskInternal = serviceDeskInternal;
    }

    /** id комментария или {@code null}, если событие не про комментарий. */
    public Long commentId() {
        return commentId;
    }

    /** Внутренний комментарий Service Desk: заказчик его не видит. */
    public boolean isServiceDeskInternal() {
        return serviceDeskInternal;
    }

    /**
     * Делегирование по такому комментарию не применяется: право видеть внутренний
     * комментарий Service Desk даёт роль агента, а делегат её не обязан иметь —
     * и штатной проверки на это у нас нет, в отличие от ограничения по группе.
     */
    public boolean blocksDelegation() {
        return serviceDeskInternal;
    }
}
