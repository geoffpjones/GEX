package clob.fix;

/** CLOB-specific FIX tags (user-defined range). See docs/FIX-RoE.md. */
final class FixTags {
    private FixTags() {}

    /** Instrument tenor, e.g. SPOT, 1M. Default SPOT. */
    static final int TENOR = 20013;
    /** Explicit CLOB order type: LIMIT, MARKET, ICEBERG, PEGGED, MID, FIXING (overrides OrdType 40). */
    static final int ORDER_TYPE = 20010;
    /** Discretion in pips (signed; positive = more aggressive) for PEGGED and MID orders. */
    static final int DISCRETION = 20011;
    /** Fixing event id for FIXING orders. */
    static final int FIXING_EVENT = 20012;
    /** Symbol in TradingSessionStatus (h). */
    static final int SYMBOL = 20014;
    /** Quantity matched for a fixing order, price to follow. */
    static final int FIXING_MATCHED_QTY = 20001;
    /** Race the order joined. */
    static final int RACE_ID = 20003;
    /** Trade identifier on fills (T{tradeId}{A|P}); the same trade id appears on both sides. */
    static final int TRADE_ID = 20004;
}
