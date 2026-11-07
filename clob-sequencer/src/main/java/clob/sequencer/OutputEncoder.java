package clob.sequencer;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.EngineListener;
import clob.engine.RejectReason;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import clob.engine.SessionState;
import java.nio.ByteOrder;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Encodes every engine event into a fixed binary layout (little-endian): {@code int length, int type,
 * long time} then the event's fields in declaration order. This is the stream gateways, market data and
 * drop copy consume, and what determinism tests compare byte for byte.
 */
public final class OutputEncoder implements EngineListener {
    public static final int ACCEPTED = 1;
    public static final int REJECTED = 2;
    public static final int TRADE = 3;
    public static final int CANCELLED = 4;
    public static final int REPLACED = 5;
    public static final int REQUEST_REJECTED = 6;
    public static final int REQUEST_QUEUED = 7;
    public static final int REPRICED = 8;
    public static final int SUSPENDED = 9;
    public static final int FIXING_MATCHED = 10;
    public static final int RACE_STARTED = 11;
    public static final int JOINED_RACE = 12;
    public static final int SESSION_STATE = 13;

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private final UnsafeBuffer buf = new UnsafeBuffer(new byte[256]);
    private final OutputSink sink;
    private int p;

    public OutputEncoder(OutputSink sink) {
        this.sink = sink;
    }

    private void begin(int type, long time) {
        buf.putInt(4, type, ORDER);
        buf.putLong(8, time, ORDER);
        p = 16;
    }

    private void i64(long v) {
        buf.putLong(p, v, ORDER);
        p += 8;
    }

    private void i32(int v) {
        buf.putInt(p, v, ORDER);
        p += 4;
    }

    private void i8(Enum<?> e) {
        buf.putByte(p, InputCodec.ord(e));
        p += 1;
    }

    private void bool(boolean v) {
        buf.putByte(p, (byte) (v ? 1 : 0));
        p += 1;
    }

    private void end() {
        buf.putInt(0, p, ORDER);
        sink.write(buf, 0, p);
    }

    @Override
    public void onOrderAccepted(long time, long orderId) {
        begin(ACCEPTED, time);
        i64(orderId);
        end();
    }

    @Override
    public void onOrderRejected(long time, long orderId, RejectReason reason) {
        begin(REJECTED, time);
        i64(orderId);
        i8(reason);
        end();
    }

    @Override
    public void onTrade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, int priceDecimals, long qty, long aggressorLeaves, long passiveLeaves) {
        begin(TRADE, time);
        i64(tradeId);
        i32(instrumentId);
        i64(aggressorOrderId);
        i64(passiveOrderId);
        i8(aggressorSide);
        i64(price);
        i32(priceDecimals);
        i64(qty);
        i64(aggressorLeaves);
        i64(passiveLeaves);
        end();
    }

    @Override
    public void onOrderCancelled(long time, long orderId, CancelReason reason, long cancelledQty) {
        begin(CANCELLED, time);
        i64(orderId);
        i8(reason);
        i64(cancelledQty);
        end();
    }

    @Override
    public void onOrderReplaced(long time, long orderId, long price, long qty, long leaves) {
        begin(REPLACED, time);
        i64(orderId);
        i64(price);
        i64(qty);
        i64(leaves);
        end();
    }

    @Override
    public void onRequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason) {
        begin(REQUEST_REJECTED, time);
        i64(orderId);
        i8(type);
        i8(reason);
        end();
    }

    @Override
    public void onRequestQueued(long time, long orderId, RequestType type, long releaseTime) {
        begin(REQUEST_QUEUED, time);
        i64(orderId);
        i8(type);
        i64(releaseTime);
        end();
    }

    @Override
    public void onRaceStarted(long time, long raceId, int instrumentId, boolean taker, Side side, long price,
            long expiry, long seed) {
        begin(RACE_STARTED, time);
        i64(raceId);
        i32(instrumentId);
        bool(taker);
        i8(side);
        i64(price);
        i64(expiry);
        i64(seed);
        end();
    }

    @Override
    public void onOrderJoinedRace(long time, long orderId, long raceId) {
        begin(JOINED_RACE, time);
        i64(orderId);
        i64(raceId);
        end();
    }

    @Override
    public void onSessionState(long time, int instrumentId, SessionState state) {
        begin(SESSION_STATE, time);
        i32(instrumentId);
        i8(state);
        end();
    }

    @Override
    public void onOrderRepriced(long time, long orderId, long price) {
        begin(REPRICED, time);
        i64(orderId);
        i64(price);
        end();
    }

    @Override
    public void onOrderSuspended(long time, long orderId) {
        begin(SUSPENDED, time);
        i64(orderId);
        end();
    }

    @Override
    public void onFixingMatched(long time, long matchId, int instrumentId, int fixingEventId, long aggressorOrderId,
            long passiveOrderId, Side aggressorSide, long qty, long aggressorLeaves, long passiveLeaves) {
        begin(FIXING_MATCHED, time);
        i64(matchId);
        i32(instrumentId);
        i32(fixingEventId);
        i64(aggressorOrderId);
        i64(passiveOrderId);
        i8(aggressorSide);
        i64(qty);
        i64(aggressorLeaves);
        i64(passiveLeaves);
        end();
    }
}
