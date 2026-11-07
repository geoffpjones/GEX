package clob.engine;

import clob.common.Side;

/**
 * Engine output. Primitive arguments only, so emitting events allocates nothing. Every event
 * carries the sequenced (logical) time at which it happened; timer-driven events carry the
 * timer's deadline.
 */
public interface EngineListener {
    void onOrderAccepted(long time, long orderId);

    void onOrderRejected(long time, long orderId, RejectReason reason);

    /** Leaves are the true remaining quantities after the fill, before any residual cancel. */
    void onTrade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, long qty, long aggressorLeaves, long passiveLeaves);

    void onOrderCancelled(long time, long orderId, CancelReason reason, long cancelledQty);

    void onOrderReplaced(long time, long orderId, long price, long qty, long leaves);

    void onRequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason);

    /** A cancel or replace held until {@code releaseTime} (min TTL). */
    void onRequestQueued(long time, long orderId, RequestType type, long releaseTime);

    /** A peg order moved to a new price, or resumed after suspension. It has new time priority. */
    void onOrderRepriced(long time, long orderId, long price);

    /** A peg order left the book because its reference price is unavailable. It is not cancelled. */
    void onOrderSuspended(long time, long orderId);

    /**
     * Two fixing orders matched; the price follows when the fixing rate arrives, as an
     * {@link #onTrade} with the same id ({@code matchId}) and leaves of -1.
     */
    void onFixingMatched(long time, long matchId, int instrumentId, int fixingEventId, long aggressorOrderId,
            long passiveOrderId, Side aggressorSide, long qty, long aggressorLeaves, long passiveLeaves);
}
