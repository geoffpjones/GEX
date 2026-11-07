package clob.engine;

public enum RejectReason {
    UNKNOWN_INSTRUMENT,
    MISSING_FIELD,
    NOT_ENTITLED,
    DUPLICATE_ORDER_ID,
    UNSUPPORTED_ORDER_TYPE,
    UNSUPPORTED_TIF,
    INVALID_QUANTITY,
    INVALID_PRICE,
    INVALID_SHOW,
    INVALID_EXPIRE_TIME,
    CAPACITY_EXCEEDED,
    /** PEGGED: no visible (non-peg) bid or offer to peg to. */
    PEG_REFERENCE_UNAVAILABLE,
    UNKNOWN_FIXING_EVENT,
    /** FIXING: the event has no open occurrence (not scheduled, or past its cut-off). */
    FIXING_NOT_OPEN,
    /** The TIF is supported by the instrument but not for this order type. */
    INVALID_TIF_FOR_TYPE,
    /** The instrument is pre-open or closed. */
    MARKET_NOT_OPEN,
    MARKET_HALTED
}
