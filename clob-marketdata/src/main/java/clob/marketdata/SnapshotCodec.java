package clob.marketdata;

import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Binary market data snapshot, little-endian (an ITCH/SBE-style stand-in):
 * {@code long subscriptionId, long sequence, long boundary, int instrumentId, int bidCount, int askCount},
 * then bid (price, qty) pairs best first, then ask pairs. Receivers detect gaps by sequence number.
 */
public final class SnapshotCodec {
    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    public static final int HEADER = 36;

    private SnapshotCodec() {}

    public static int length(BookView v) {
        return HEADER + 16 * (v.bidCount + v.askCount);
    }

    public static int encode(MutableDirectBuffer b, int o, long subscriptionId, long sequence, long boundary, BookView v) {
        b.putLong(o, subscriptionId, ORDER);
        b.putLong(o + 8, sequence, ORDER);
        b.putLong(o + 16, boundary, ORDER);
        b.putInt(o + 24, v.instrumentId, ORDER);
        b.putInt(o + 28, v.bidCount, ORDER);
        b.putInt(o + 32, v.askCount, ORDER);
        int p = o + HEADER;
        for (int i = 0; i < v.bidCount; i++, p += 16) {
            b.putLong(p, v.bidPx[i], ORDER);
            b.putLong(p + 8, v.bidQty[i], ORDER);
        }
        for (int i = 0; i < v.askCount; i++, p += 16) {
            b.putLong(p, v.askPx[i], ORDER);
            b.putLong(p + 8, v.askQty[i], ORDER);
        }
        return p - o;
    }

    public static long subscriptionId(DirectBuffer b, int o) { return b.getLong(o, ORDER); }
    public static long sequence(DirectBuffer b, int o) { return b.getLong(o + 8, ORDER); }
    public static long boundary(DirectBuffer b, int o) { return b.getLong(o + 16, ORDER); }

    /** Decodes the levels into {@code v}. */
    public static void decode(DirectBuffer b, int o, BookView v) {
        int bidCount = b.getInt(o + 28, ORDER);
        int askCount = b.getInt(o + 32, ORDER);
        BookView tmp = new BookView(Math.max(1, Math.max(bidCount, askCount)));
        tmp.instrumentId = b.getInt(o + 24, ORDER);
        tmp.bidCount = bidCount;
        tmp.askCount = askCount;
        int p = o + HEADER;
        for (int i = 0; i < bidCount; i++, p += 16) {
            tmp.bidPx[i] = b.getLong(p, ORDER);
            tmp.bidQty[i] = b.getLong(p + 8, ORDER);
        }
        for (int i = 0; i < askCount; i++, p += 16) {
            tmp.askPx[i] = b.getLong(p, ORDER);
            tmp.askQty[i] = b.getLong(p + 8, ORDER);
        }
        v.copyFrom(tmp, Integer.MAX_VALUE);
    }

    /** A new empty view to decode into. */
    public static BookView newView() {
        return new BookView(16);
    }
}
