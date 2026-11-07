package clob.engine;

public enum RequestRejectReason {
    UNKNOWN_ORDER,
    NOT_ENTITLED,
    INSIDE_MIN_TTL,
    CANCEL_ALREADY_PENDING,
    /** The order finished (filled or cancelled) before a queued request could be applied. */
    TOO_LATE,
    /** A queued replace was overtaken by a later cancel or replace. */
    SUPERSEDED,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INVALID_SHOW,
    QTY_NOT_ABOVE_FILLED,
    LEAVES_BELOW_MIN_QTY,
    NOT_REPLACEABLE,
    /** The order is waiting in a race lottery; it can be amended once the race resolves. */
    PENDING_IN_RACE
}
