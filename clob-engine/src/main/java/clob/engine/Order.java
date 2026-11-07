package clob.engine;

import clob.common.Side;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;

/** Pooled, mutable order state. Package-private: only the engine touches it. */
final class Order {
    static final long FULLY_VISIBLE = Long.MAX_VALUE;

    long orderId;
    int instrumentId;
    int accountId;
    int userId;
    Side side;
    OrderType type;
    TimeInForce tif;
    long price;
    /** Total order quantity (FIX OrderQty). */
    long qty;
    long cumQty;
    long leaves;
    /** Max show; {@link #FULLY_VISIBLE} for a fully displayed order, 0 for a fully hidden one. */
    long maxShow;
    /** Remaining displayed slice; 0 for a hidden order. Invariant: 0 or >= minQty. */
    long displayQty;
    long expireTime;
    boolean gttScheduled;
    /** Start of the current min TTL window (acceptance, or the last applied amend). */
    long ttlStart;
    long prioritySeq;
    /** Residual below min qty cancelled at the end of this order's aggression. */
    long residualCancelQty;

    RequestType pendingType;
    long pendingRelease;
    long pendingPrice;
    long pendingQty;
    long pendingMaxShow;

    Order prev;
    Order next;
    PriceLevel level;
    boolean inVisibleQueue;

    void reset() {
        orderId = 0;
        instrumentId = 0;
        accountId = 0;
        userId = 0;
        side = null;
        type = null;
        tif = null;
        price = 0;
        qty = 0;
        cumQty = 0;
        leaves = 0;
        maxShow = 0;
        displayQty = 0;
        expireTime = 0;
        gttScheduled = false;
        ttlStart = 0;
        prioritySeq = 0;
        residualCancelQty = 0;
        pendingType = null;
        pendingRelease = 0;
        pendingPrice = 0;
        pendingQty = 0;
        pendingMaxShow = 0;
        prev = null;
        next = null;
        level = null;
        inVisibleQueue = false;
    }
}
