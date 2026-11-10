package clob.engine;

import clob.common.Side;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
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
    long pendingDiscretion;
    long pendingRequestId;
    /** The participant cancel request being applied; reported with the cancel. */
    long cancelRequestId;

    /** PEGGED and MID: pips; positive is more aggressive. */
    long discretion;
    PegReference pegReference;
    /** PEGGED: off the book because the reference is unavailable. */
    boolean suspended;
    /** PEGGED: membership of the book's peg list (arrival order). */
    Order pegPrev;
    Order pegNext;
    boolean inPegList;
    /** FIXING: the fixing event. */
    int fixingEventId;
    /** MID, FIXING and race tickets: the queue holding the order (instead of a price level). */
    OrderQueue queue;
    /** Newly submitted and not yet processed (only new orders may start or join a maker race). */
    boolean fresh;
    /** A queued cancel/replace also waiting for a taker race to finish (queue-cancels). */
    boolean heldByRace;
    /** The taker race holding the request; the hold ends when that race resolves. */
    long holdUntilRace;
    long holdRaceExpiry;
    /** Waiting in a taker race ticket. */
    boolean inTakerRace;
    /** Reached a resting order of its own account or legal entity; its remainder is cancelled. */
    boolean selfTradeHit;

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
        pendingDiscretion = 0;
        pendingRequestId = 0;
        cancelRequestId = 0;
        discretion = 0;
        pegReference = null;
        suspended = false;
        pegPrev = null;
        pegNext = null;
        inPegList = false;
        fixingEventId = 0;
        queue = null;
        fresh = false;
        heldByRace = false;
        holdUntilRace = 0;
        holdRaceExpiry = 0;
        inTakerRace = false;
        selfTradeHit = false;
        prev = null;
        next = null;
        level = null;
        inVisibleQueue = false;
    }
}
