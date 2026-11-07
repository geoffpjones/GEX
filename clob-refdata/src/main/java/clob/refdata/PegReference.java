package clob.refdata;

/** What a pegged order tracks. Both use the visible top of book excluding peg orders (confirmed). */
public enum PegReference {
    /** Midpoint of the best visible bid and offer. */
    MID,
    /** Same-side best: the best bid for a buy, the best offer for a sell. */
    NEAR_TOUCH
}
