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
    END_OF_DAY,
    /** FIXING: unmatched quantity at the event's cut-off. */
    FIXING_CUTOFF,
    /** The aggressor reached a resting order of its own account or legal entity. */
    SELF_TRADE,
    /** The order was waiting in a race when the instrument halted. */
    HALTED
}
