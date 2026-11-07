package clob.engine;

import clob.common.Side;

/** Discards every event. For replica engines (market data) that only need the engine's state. */
public final class NullEngineListener implements EngineListener {
    public static final NullEngineListener INSTANCE = new NullEngineListener();

    private NullEngineListener() {}

    @Override public void onOrderAccepted(long time, long orderId) { }
    @Override public void onOrderRejected(long time, long orderId, RejectReason reason) { }
    @Override public void onTrade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, int priceDecimals, long qty, long aggressorLeaves, long passiveLeaves) { }
    @Override public void onOrderCancelled(long time, long orderId, CancelReason reason, long cancelledQty) { }
    @Override public void onOrderReplaced(long time, long orderId, long price, long qty, long leaves) { }
    @Override public void onRequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason) { }
    @Override public void onRequestQueued(long time, long orderId, RequestType type, long releaseTime) { }
    @Override public void onRaceStarted(long time, long raceId, int instrumentId, boolean taker, Side side, long price,
            long expiry, long seed) { }
    @Override public void onOrderJoinedRace(long time, long orderId, long raceId) { }
    @Override public void onSessionState(long time, int instrumentId, SessionState state) { }
    @Override public void onOrderRepriced(long time, long orderId, long price) { }
    @Override public void onOrderSuspended(long time, long orderId) { }
    @Override public void onFixingMatched(long time, long matchId, int instrumentId, int fixingEventId,
            long aggressorOrderId, long passiveOrderId, Side aggressorSide, long qty, long aggressorLeaves,
            long passiveLeaves) { }
}
