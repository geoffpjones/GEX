package clob.engine;

import clob.common.Side;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.TimeInForce;

/** Mutable, reusable new-order input (decoded from SBE in production). */
public final class NewOrderCommand {
    /** Marker for "no max show": the whole order is displayed. */
    public static final long FULLY_VISIBLE = -1;

    public long orderId;
    public int instrumentId;
    public int accountId;
    public int userId;
    public Side side;
    public OrderType type;
    public TimeInForce tif;
    public long price;
    public long qty;
    public long maxShow = FULLY_VISIBLE;
    public long expireTime;
    /** PEGGED and MID: pips, positive = more aggressive. */
    public long discretion;
    /** PEGGED only. */
    public PegReference pegReference;
    /** FIXING only. */
    public int fixingEventId = -1;

    public NewOrderCommand reset() {
        orderId = 0;
        instrumentId = 0;
        accountId = 0;
        userId = 0;
        side = null;
        type = null;
        tif = null;
        price = 0;
        qty = 0;
        maxShow = FULLY_VISIBLE;
        expireTime = 0;
        discretion = 0;
        pegReference = null;
        fixingEventId = -1;
        return this;
    }
}
