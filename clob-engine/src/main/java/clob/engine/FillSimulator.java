package clob.engine;

import clob.credit.CreditEngine;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import java.util.Arrays;

/**
 * Dry run of {@link MatchingEngine}'s traversal, used for FOK. It must mirror the real walk exactly:
 * screened VISIBILITY unlock, skipping orders it cannot trade with, clipping fills to credit,
 * iceberg refreshes, passive residual removal and aggressor residual cancellation. Credit is
 * simulated through {@link CreditEngine#beginSimulation()}, so shared pools are exact too.
 * {@code RandomFlowTest} checks FOK against IOC on random books with random credit.
 */
final class FillSimulator {
    private final CreditEngine credit;

    private long[] leaves = new long[64];
    private long[] display = new long[64];
    private long[] maxShow = new long[64];
    private int[] account = new int[64];
    private int[] ring = new int[64];

    private Order agg;
    private Instrument instrument;
    private long remaining;
    private long filled;
    private boolean killed;

    FillSimulator(CreditEngine credit) {
        this.credit = credit;
    }

    /** Quantity the aggressor would fill if matched now; less than its leaves if it would not complete. */
    long fillable(Order aggressor, OrderBook book) {
        credit.beginSimulation();
        try {
            return run(aggressor, book);
        } finally {
            credit.endSimulation();
            agg = null;
        }
    }

    private long run(Order aggressor, OrderBook book) {
        BookSide opp = book.side(aggressor.side.opposite());
        agg = aggressor;
        instrument = book.instrument;
        remaining = aggressor.leaves;
        filled = 0;
        killed = false;
        int top = -1;
        if (instrument.matchPriority() == MatchPriority.VISIBILITY) {
            for (int i = 0; i < opp.size() && !done(); i++) {
                PriceLevel level = opp.levelAt(i);
                if (!MatchingEngine.crosses(agg, level.price)) {
                    break;
                }
                if (simulateVisible(level) > 0) {
                    top = i;
                    break;
                }
            }
            if (top < 0 || done()) {
                return filled;
            }
        }
        for (int i = 0; i < opp.size() && !done(); i++) {
            PriceLevel level = opp.levelAt(i);
            if (!MatchingEngine.crosses(agg, level.price)) {
                break;
            }
            // Levels up to the unlocking one already had their displayed queues walked in the first
            // pass; credit only tightens during one aggression, so nothing more is fillable there.
            if (i > top) {
                simulateVisible(level);
            }
            simulateHidden(level);
        }
        return filled;
    }

    private boolean done() {
        return remaining == 0 || killed;
    }

    /** Fill quantity against an order, or 0 if the real walk would skip it. Records the fill. */
    private long tryTake(int passiveAccount, long available) {
        long qty = Math.min(remaining, available);
        long cap = credit.capacity(agg.accountId, passiveAccount, instrument.id(), agg.side);
        if (cap < qty) {
            qty = MatchingEngine.roundDownToExecutionSize(cap, instrument);
            if (qty == 0) {
                return 0;
            }
        }
        credit.commit(agg.accountId, passiveAccount, instrument.id(), agg.side, qty);
        filled += qty;
        remaining -= qty;
        if (remaining > 0 && remaining < instrument.minQty()) {
            killed = true;
        }
        return qty;
    }

    /**
     * Visit order matches the real cursor walk: each order once, front to back; an order whose slice
     * is refreshed moves to the back and is visited again; skipped orders are not revisited.
     */
    private int simulateVisible(PriceLevel level) {
        int n = 0;
        for (Order o = level.visHead; o != null; o = o.next) {
            ensureCapacity(n + 1);
            leaves[n] = o.leaves;
            display[n] = o.displayQty;
            maxShow[n] = o.maxShow;
            account[n] = o.accountId;
            ring[n] = n;
            n++;
        }
        long minQty = instrument.minQty();
        int fills = 0;
        int head = 0;
        int count = n;
        while (count > 0 && !done()) {
            int idx = ring[head];
            long qty = tryTake(account[idx], display[idx]);
            if (qty == 0) {
                head = (head + 1) % n;
                count--;
                continue;
            }
            fills++;
            leaves[idx] -= qty;
            display[idx] -= qty;
            if (leaves[idx] >= minQty && display[idx] < minQty) {
                display[idx] = Math.min(maxShow[idx], leaves[idx]);
                ring[(head + count) % n] = idx;
                head = (head + 1) % n;
            } else {
                head = (head + 1) % n;
                count--;
            }
        }
        return fills;
    }

    private void simulateHidden(PriceLevel level) {
        for (Order o = level.hidHead; o != null && !done(); o = o.next) {
            tryTake(o.accountId, o.leaves);
        }
    }

    private void ensureCapacity(int n) {
        if (n > leaves.length) {
            int c = Math.max(n, leaves.length * 2);
            leaves = Arrays.copyOf(leaves, c);
            display = Arrays.copyOf(display, c);
            maxShow = Arrays.copyOf(maxShow, c);
            account = Arrays.copyOf(account, c);
            ring = Arrays.copyOf(ring, c);
        }
    }
}
