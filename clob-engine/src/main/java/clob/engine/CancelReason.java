package clob.engine;

public enum CancelReason {
    PARTICIPANT,
    DISCONNECT,
    ADMIN,
    IOC_UNFILLED,
    FOK_UNFILLED,
    MARKET_UNFILLED,
    /** Positive residual below min quantity after a fill (confirmed rule, PLAN Q6f). */
    RESIDUAL_BELOW_MIN_QTY,
    EXPIRED,
    END_OF_DAY
}
