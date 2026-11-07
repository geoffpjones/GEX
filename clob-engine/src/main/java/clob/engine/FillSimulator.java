package clob.engine;

import clob.refdata.MatchPriority;
import java.util.Arrays;

/**
 * Dry run of {@link MatchingEngine}'s traversal, used for FOK. It must mirror the real traversal
 * exactly, including iceberg refreshes, passive residual removal and aggressor residual
 * cancellation. {@code FokIocEquivalenceTest} checks the two against each other on random books.
 */
final class FillSimulator {
    private long[] leaves = new long[64];
    private long[] display = new long[64];
    private long[] maxShow = new long[64];
    private int[] ring = new int[64];

    private long remaining;
    private long filled;
    private long minQty;
    private boolean killed;

    /** Quantity the aggressor would fill if matched now; less than its leaves if it would not complete. */
    long fillable(Order agg, OrderBook book) {
        BookSide opp = book.side(agg.side.opposite());
        remaining = agg.leaves;
        filled = 0;
        killed = false;
        minQty = book.instrument.minQty();
        int top = -1;
        if (book.instrument.matchPriority() == MatchPriority.VISIBILITY) {
            top = opp.firstVisibleIndex();
            if (top < 0 || !MatchingEngine.crosses(agg, opp.levelAt(top).price)) {
                return 0;
            }
            simulateVisible(opp.levelAt(top));
            if (done()) {
                return filled;
            }
        }
        for (int i = 0; i < opp.size() && !done(); i++) {
            PriceLevel level = opp.levelAt(i);
            if (!MatchingEngine.crosses(agg, level.price)) {
                break;
            }
            if (i != top) {
                simulateVisible(level);
            }
            simulateHidden(level);
        }
        return filled;
    }

    private boolean done() {
        return remaining == 0 || killed;
    }

    private void take(long qty) {
        filled += qty;
        remaining -= qty;
        if (remaining > 0 && remaining < minQty) {
            killed = true;
        }
    }

    private void simulateVisible(PriceLevel level) {
        int n = 0;
        for (Order o = level.visHead; o != null; o = o.next) {
            ensureCapacity(n + 1);
            leaves[n] = o.leaves;
            display[n] = o.displayQty;
            maxShow[n] = o.maxShow;
            ring[n] = n;
            n++;
        }
        int head = 0;
        int count = n;
        while (count > 0 && !done()) {
            int idx = ring[head];
            long qty = Math.min(remaining, display[idx]);
            take(qty);
            leaves[idx] -= qty;
            display[idx] -= qty;
            if (leaves[idx] < minQty) {
                head = (head + 1) % n;
                count--;
            } else if (display[idx] < minQty) {
                display[idx] = Math.min(maxShow[idx], leaves[idx]);
                ring[(head + count) % n] = idx;
                head = (head + 1) % n;
            }
        }
    }

    private void simulateHidden(PriceLevel level) {
        for (Order o = level.hidHead; o != null && !done(); o = o.next) {
            take(Math.min(remaining, o.leaves));
        }
    }

    private void ensureCapacity(int n) {
        if (n > leaves.length) {
            int c = Math.max(n, leaves.length * 2);
            leaves = Arrays.copyOf(leaves, c);
            display = Arrays.copyOf(display, c);
            maxShow = Arrays.copyOf(maxShow, c);
            ring = Arrays.copyOf(ring, c);
        }
    }
}
