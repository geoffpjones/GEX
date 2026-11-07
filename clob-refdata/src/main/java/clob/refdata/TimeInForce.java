package clob.refdata;

public enum TimeInForce {
    /** Cancelled at end of day. */
    DAY,
    /** Rolled at end of day to the next session. */
    GTC,
    /** Cancelled at a specific time. */
    GTT,
    /** Filled entirely or not at all. */
    FOK,
    /** Never rests; any unfilled quantity is cancelled. */
    IOC
}
