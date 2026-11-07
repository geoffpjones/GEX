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
 * Stands in for the output path: every event is encoded into an off-heap ring (as an SBE encoder
 * into an Aeron publication would be). Optionally runs a credit-check stub on every trade: a
 * bilateral eligibility bitset test plus DSL accumulation for both directions in dense arrays. That is the
 * expected per-fill credit work, but done after the fill rather than inside the matching walk.
 */
final class SpikeListener implements EngineListener {
    private static final int RING_BYTES = 8 << 20;
    private static final int RECORD = 64;

    private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(RING_BYTES));
    private int position;

    private final boolean credit;
    private final int accounts;
    private final long[][] eligible;
    /** DSL usage per directed pair and value-date bucket, dense (pool ids are assigned at config time). */
    private final long[] dslUsage;
    private static final int VALUE_DATES = 8;
    private final long dslLimit = Long.MAX_VALUE / 4;

    long accepted;
    long rejected;
    long trades;
    long cancels;
    long requestRejects;
    long queued;
    long replaced;
    long creditChecks;
    long ineligiblePairs;

    SpikeListener(boolean credit, int accounts, long seed) {
        this.credit = credit;
        this.accounts = accounts;
        this.eligible = new long[accounts + 1][(accounts + 64) / 64];
        long r = seed;
        for (int a = 1; a <= accounts; a++) {
            for (int b = 1; b <= accounts; b++) {
                r = r * 6364136223846793005L + 1442695040888963407L;
                if ((r >>> 33) % 10 != 0) { // 90% of directed pairs extend credit
                    eligible[a][b >>> 6] |= 1L << (b & 63);
                }
            }
        }
        this.dslUsage = new long[(accounts + 1) * (accounts + 1) * VALUE_DATES];
    }

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
            Side aggressorSide, long price, long qty, long aggressorLeaves, long passiveLeaves) {
        if (credit) {
            creditCheck(aggressorOrderId, passiveOrderId, instrumentId, price, qty);
        }
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

    private void creditCheck(long aggId, long passId, int instrumentId, long price, long qty) {
        int a = FlowGenerator.accountOf(aggId, accounts);
        int b = FlowGenerator.accountOf(passId, accounts);
        creditChecks++;
        boolean ok = (eligible[a][b >>> 6] & (1L << (b & 63))) != 0
                && (eligible[b][a >>> 6] & (1L << (a & 63))) != 0;
        if (!ok) {
            ineligiblePairs++;
            return;
        }
        long usd = qty; // USD conversion stub: base notional
        int valueDate = instrumentId & (VALUE_DATES - 1);
        int ab = (a * (accounts + 1) + b) * VALUE_DATES + valueDate;
        int ba = (b * (accounts + 1) + a) * VALUE_DATES + valueDate;
        long newAb = dslUsage[ab] + usd;
        long newBa = dslUsage[ba] + usd;
        if (newAb <= dslLimit && newBa <= dslLimit) {
            dslUsage[ab] = newAb;
            dslUsage[ba] = newBa;
        }
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
    public void onRequestQueued(long time, long orderId, RequestType type, long releaseTime) {
        int p = claim();
        out.putByte(p, (byte) 7);
        out.putLong(p + 8, time);
        out.putLong(p + 16, orderId);
        queued++;
    }
}
