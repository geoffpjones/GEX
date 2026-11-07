package clob.engine;

/**
 * Trading state of one instrument. Only {@link #OPEN} accepts new orders and amends and runs matching.
 * Cancels are accepted in every state. While not open, peg reprices, mid matching and race lotteries
 * wait: a race that expires while halted resolves when the instrument reopens.
 */
public enum SessionState {
    PRE_OPEN,
    OPEN,
    HALTED,
    CLOSED
}
