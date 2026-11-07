package clob.marketdata;

import java.util.Arrays;

/**
 * Aggregated price levels for one instrument as one subscriber sees it. Prices are in instrument ticks,
 * bucketed to the market data price increment; quantities are displayed quantities only.
 */
public final class BookView {
    int instrumentId;
    int bidCount;
    int askCount;
    long[] bidPx;
    long[] bidQty;
    long[] askPx;
    long[] askQty;

    BookView(int levels) {
        bidPx = new long[levels];
        bidQty = new long[levels];
        askPx = new long[levels];
        askQty = new long[levels];
    }

    public int instrumentId() { return instrumentId; }
    public int bidCount() { return bidCount; }
    public int askCount() { return askCount; }
    public long bidPrice(int i) { return bidPx[i]; }
    public long bidQty(int i) { return bidQty[i]; }
    public long askPrice(int i) { return askPx[i]; }
    public long askQty(int i) { return askQty[i]; }

    void clear(int instrumentId) {
        this.instrumentId = instrumentId;
        bidCount = 0;
        askCount = 0;
    }

    /** Copies at most {@code depth} levels per side from {@code src}. */
    void copyFrom(BookView src, int depth) {
        instrumentId = src.instrumentId;
        bidCount = Math.min(depth, src.bidCount);
        askCount = Math.min(depth, src.askCount);
        ensure(Math.max(bidCount, askCount));
        System.arraycopy(src.bidPx, 0, bidPx, 0, bidCount);
        System.arraycopy(src.bidQty, 0, bidQty, 0, bidCount);
        System.arraycopy(src.askPx, 0, askPx, 0, askCount);
        System.arraycopy(src.askQty, 0, askQty, 0, askCount);
    }

    void ensure(int n) {
        if (n > bidPx.length) {
            bidPx = Arrays.copyOf(bidPx, n);
            bidQty = Arrays.copyOf(bidQty, n);
            askPx = Arrays.copyOf(askPx, n);
            askQty = Arrays.copyOf(askQty, n);
        }
    }

    boolean sameAs(BookView o) {
        return instrumentId == o.instrumentId && bidCount == o.bidCount && askCount == o.askCount
                && Arrays.equals(bidPx, 0, bidCount, o.bidPx, 0, bidCount)
                && Arrays.equals(bidQty, 0, bidCount, o.bidQty, 0, bidCount)
                && Arrays.equals(askPx, 0, askCount, o.askPx, 0, askCount)
                && Arrays.equals(askQty, 0, askCount, o.askQty, 0, askCount);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("bids[");
        for (int i = 0; i < bidCount; i++) {
            sb.append(i == 0 ? "" : " ").append(bidPx[i]).append('x').append(bidQty[i]);
        }
        sb.append("] asks[");
        for (int i = 0; i < askCount; i++) {
            sb.append(i == 0 ? "" : " ").append(askPx[i]).append('x').append(askQty[i]);
        }
        return sb.append(']').toString();
    }
}
