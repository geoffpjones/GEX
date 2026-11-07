package clob.bench;

/**
 * Deterministic synthetic order flow for many books, shaped like a market-maker-dominated FX venue:
 * 45% passive adds (10% iceberg, 10% hidden), 40% cancels, 10% price amends, 5% aggressive IOCs.
 * Each book's mid occasionally drifts, so resting orders get crossed. The generator tracks at most
 * {@value #RING} live orders per book, which bounds book depth.
 *
 * <p>Events are generated a chunk at a time into primitive arrays, outside the timed section.
 */
final class FlowGenerator {
    static final byte NEW_PASSIVE = 0;
    static final byte NEW_AGGRESSIVE = 1;
    static final byte CANCEL = 2;
    static final byte REPLACE = 3;

    static final int RING = 64;
    static final long MIN_QTY = 1_000_000L;

    final int chunk;
    final byte[] kind;
    final int[] instrument;
    final long[] orderId;
    final boolean[] buy;
    final long[] price;
    final long[] qty;
    final long[] show;

    private final int books;
    private final int accounts;
    private final long[] mid;
    private final long[][] liveIds;
    private final long[][] livePrice;
    private final long[][] liveQty;
    private final boolean[][] liveBuy;
    private final int[] liveCount;
    private long rng;
    private long nextOrderId = 1;

    FlowGenerator(int books, int accounts, int chunk, long seed) {
        this.books = books;
        this.accounts = accounts;
        this.chunk = chunk;
        kind = new byte[chunk];
        instrument = new int[chunk];
        orderId = new long[chunk];
        buy = new boolean[chunk];
        price = new long[chunk];
        qty = new long[chunk];
        show = new long[chunk];
        mid = new long[books];
        liveIds = new long[books][RING];
        livePrice = new long[books][RING];
        liveQty = new long[books][RING];
        liveBuy = new boolean[books][RING];
        liveCount = new int[books];
        for (int b = 0; b < books; b++) {
            mid[b] = 100_000 + 1_000L * b;
        }
        rng = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    /** Account that owns an order. A pure function of the id, so the credit stub can use it too. */
    static int accountOf(long orderId, int accounts) {
        return 1 + (int) Math.floorMod(orderId * 0x9E3779B97F4A7C15L >>> 17, (long) accounts);
    }

    int accounts() {
        return accounts;
    }

    private int next(int bound) {
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        return (int) Math.floorMod(rng, (long) bound);
    }

    void fill() {
        for (int i = 0; i < chunk; i++) {
            int b = next(books);
            instrument[i] = b;
            if (next(100) == 0) {
                mid[b] += next(2) == 0 ? -1 : 1;
            }
            int roll = next(100);
            int n = liveCount[b];
            if (roll < 45 && n < RING || roll >= 45 && n == 0 && roll < 95) {
                passive(i, b);
            } else if (roll < 85) {
                cancel(i, b);
            } else if (roll < 95) {
                replace(i, b);
            } else {
                aggressive(i, b);
            }
        }
    }

    private void passive(int i, int b) {
        boolean isBuy = next(2) == 0;
        long p = isBuy ? mid[b] - 1 - next(8) : mid[b] + 1 + next(8);
        long q = MIN_QTY * (1 + next(5));
        int s = next(10);
        kind[i] = NEW_PASSIVE;
        orderId[i] = nextOrderId++;
        buy[i] = isBuy;
        price[i] = p;
        qty[i] = q;
        show[i] = s == 0 ? 0 : (s == 1 && q >= 2 * MIN_QTY ? MIN_QTY : -1);
        int n = liveCount[b]++;
        liveIds[b][n] = orderId[i];
        livePrice[b][n] = p;
        liveQty[b][n] = q;
        liveBuy[b][n] = isBuy;
    }

    private void aggressive(int i, int b) {
        boolean isBuy = next(2) == 0;
        kind[i] = NEW_AGGRESSIVE;
        orderId[i] = nextOrderId++;
        buy[i] = isBuy;
        price[i] = isBuy ? mid[b] + 1 + next(3) : mid[b] - 1 - next(3);
        qty[i] = MIN_QTY * (1 + next(5));
        show[i] = -1;
    }

    private void cancel(int i, int b) {
        int k = next(liveCount[b]);
        kind[i] = CANCEL;
        orderId[i] = liveIds[b][k];
        removeLive(b, k);
    }

    private void replace(int i, int b) {
        int k = next(liveCount[b]);
        kind[i] = REPLACE;
        orderId[i] = liveIds[b][k];
        boolean isBuy = liveBuy[b][k];
        long p = livePrice[b][k] + (next(2) == 0 ? -1 : 1);
        if (isBuy && p >= mid[b]) {
            p = mid[b] - 1;
        } else if (!isBuy && p <= mid[b]) {
            p = mid[b] + 1;
        }
        livePrice[b][k] = p;
        buy[i] = isBuy;
        price[i] = p;
        qty[i] = liveQty[b][k];
        show[i] = -1;
    }

    private void removeLive(int b, int k) {
        int last = --liveCount[b];
        liveIds[b][k] = liveIds[b][last];
        livePrice[b][k] = livePrice[b][last];
        liveQty[b][k] = liveQty[b][last];
        liveBuy[b][k] = liveBuy[b][last];
    }
}
