package clob.bench;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.EngineListener;
import clob.engine.RejectReason;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Stands in for the output path: every event is encoded into an off-heap ring, as an SBE encoder
 * into an Aeron publication would be.
 */
final class SpikeListener implements EngineListener {
    private static final int RING_BYTES = 8 << 20;
    private static final int RECORD = 64;

    private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(RING_BYTES));
    private int position;

    long accepted;
    long rejected;
    long trades;
    long cancels;
    long requestRejects;
    long queued;
    long replaced;

    private int claim() {
        if (position + RECORD > RING_BYTES) {
            position = 0;
        }
        int p = position;
        position += RECORD;
        return p;
    }

    @Override
    public void onOrderAccepted(long time, long orderId) {
        int p = claim();
        out.putByte(p, (byte) 1);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        accepted++;
    }

    @Override
    public void onOrderRejected(long time, long orderId, RejectReason reason) {
        int p = claim();
        out.putByte(p, (byte) 2);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        out.putByte(p + 24, (byte) reason.ordinal());
        rejected++;
    }

    @Override
    public void onTrade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, int priceDecimals, long qty, long aggressorLeaves, long passiveLeaves) {
        int p = claim();
        out.putByte(p, (byte) 3);
        out.putInt(p + 4, instrumentId);
        out.putLong(p + 8, time);
        out.putLong(p + 16, tradeId);
        out.putLong(p + 24, aggressorOrderId);
        out.putLong(p + 32, passiveOrderId);
        out.putLong(p + 40, price);
        out.putLong(p + 48, qty);
        out.putLong(p + 56, aggressorLeaves ^ passiveLeaves);
        trades++;
    }

    @Override
    public void onOrderCancelled(long time, long orderId, CancelReason reason, long cancelledQty) {
        int p = claim();
        out.putByte(p, (byte) 4);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        out.putLong(p + 24, cancelledQty);
        cancels++;
    }

    @Override
    public void onOrderReplaced(long time, long orderId, long price, long qty, long leaves) {
        int p = claim();
        out.putByte(p, (byte) 5);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        out.putLong(p + 24, price);
        out.putLong(p + 32, qty);
        replaced++;
    }

    @Override
    public void onRequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason) {
        int p = claim();
        out.putByte(p, (byte) 6);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        requestRejects++;
    }

    @Override
    public void onOrderRepriced(long time, long orderId, long price) {
        int p = claim();
        out.putByte(p, (byte) 8);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        out.putLong(p + 24, price);
    }

    @Override
    public void onOrderSuspended(long time, long orderId) {
        int p = claim();
        out.putByte(p, (byte) 9);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
    }

    @Override
    public void onFixingMatched(long time, long matchId, int instrumentId, int fixingEventId, long aggressorOrderId,
            long passiveOrderId, Side aggressorSide, long qty, long aggressorLeaves, long passiveLeaves) {
        int p = claim();
        out.putByte(p, (byte) 10);
        out.putInt(p + 4, instrumentId);
        out.putLong(p + 8, time);
        out.putLong(p + 16, matchId);
        out.putLong(p + 24, aggressorOrderId);
        out.putLong(p + 32, passiveOrderId);
        out.putLong(p + 40, qty);
    }

    @Override
    public void onRequestQueued(long time, long orderId, RequestType type, long releaseTime) {
        int p = claim();
        out.putByte(p, (byte) 7);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        queued++;
    }
}
