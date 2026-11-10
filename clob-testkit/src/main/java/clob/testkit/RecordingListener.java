package clob.testkit;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.EngineListener;
import clob.engine.RejectReason;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import java.util.ArrayList;
import java.util.List;

/** Records every engine event in order. */
public final class RecordingListener implements EngineListener {
    private final List<Event> events = new ArrayList<>();
    private final List<String> requestLog = new ArrayList<>();

    public List<Event> events() {
        return events;
    }

    /** "<kind> order=<id> request=<id>" for each event correlated to a request, in order. */
    public List<String> requestLog() {
        return requestLog;
    }

    public void clear() {
        requestLog.clear();
        events.clear();
    }

    @Override
    public void onOrderAccepted(long time, long orderId) {
        events.add(new Event.Accepted(time, orderId));
    }

    @Override
    public void onOrderRejected(long time, long orderId, RejectReason reason) {
        events.add(new Event.Rejected(time, orderId, reason));
    }

    @Override
    public void onTrade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, int priceDecimals, long qty, long aggressorLeaves, long passiveLeaves) {
        events.add(new Event.Trade(time, tradeId, instrumentId, aggressorOrderId, passiveOrderId, aggressorSide,
                price, priceDecimals, qty, aggressorLeaves, passiveLeaves));
    }

    @Override
    public void onOrderCancelled(long time, long orderId, CancelReason reason, long cancelledQty, long requestId) {
        log("CANCELLED", orderId, requestId);
        events.add(new Event.Cancelled(time, orderId, reason, cancelledQty));
    }

    @Override
    public void onOrderReplaced(long time, long orderId, long price, long qty, long leaves, long requestId) {
        log("REPLACED", orderId, requestId);
        events.add(new Event.Replaced(time, orderId, price, qty, leaves));
    }

    @Override
    public void onRequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason, long requestId) {
        log("REJECTED_" + reason, orderId, requestId);
        events.add(new Event.RequestRejected(time, orderId, type, reason));
    }

    @Override
    public void onRequestQueued(long time, long orderId, RequestType type, long releaseTime, long requestId) {
        log("QUEUED", orderId, requestId);
        events.add(new Event.Queued(time, orderId, type, releaseTime));
    }

    @Override
    public void onRaceStarted(long time, long raceId, int instrumentId, boolean taker, Side side, long price,
            long expiry, long seed) {
        events.add(new Event.RaceStarted(time, raceId, instrumentId, taker, side, price, expiry, seed));
    }

    @Override
    public void onOrderJoinedRace(long time, long orderId, long raceId) {
        events.add(new Event.JoinedRace(time, orderId, raceId));
    }

    @Override
    public void onSessionState(long time, int instrumentId, clob.engine.SessionState state) {
        events.add(new Event.Session(time, instrumentId, state));
    }

    @Override
    public void onOrderRepriced(long time, long orderId, long price) {
        events.add(new Event.Repriced(time, orderId, price));
    }

    @Override
    public void onOrderSuspended(long time, long orderId) {
        events.add(new Event.Suspended(time, orderId));
    }

    @Override
    public void onFixingMatched(long time, long matchId, int instrumentId, int fixingEventId, long aggressorOrderId,
            long passiveOrderId, Side aggressorSide, long qty, long aggressorLeaves, long passiveLeaves) {
        events.add(new Event.FixingMatched(time, matchId, instrumentId, fixingEventId, aggressorOrderId,
                passiveOrderId, aggressorSide, qty, aggressorLeaves, passiveLeaves));
    }

    private void log(String kind, long orderId, long requestId) {
        if (requestId != 0) {
            requestLog.add(kind + " order=" + orderId + " request=" + requestId);
        }
    }
}
