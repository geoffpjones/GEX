package clob.refdata;

/**
 * How hidden liquidity competes with visible liquidity.
 *
 * <ul>
 *   <li>{@link #VISIBILITY}: hidden liquidity unlocks for an aggressor only once the visible
 *       top-of-book level (all displayed quantity at that price) is exhausted. After that,
 *       price-visibility-time.
 *   <li>{@link #PRICE}: price, then visible before hidden at the same price, then time.
 * </ul>
 */
public enum MatchPriority {
    VISIBILITY,
    PRICE
}
