package clob.sequencer;

import java.nio.ByteOrder;
import org.agrona.DirectBuffer;

/**
 * Decodes one {@link OutputEncoder} event into reusable fields (allocation-free). Only the fields of
 * the decoded {@link #type} are meaningful.
 */
public final class OutputReader {
    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    public int type;
    public long time;
    public long orderId;
    public int reason;
    public long tradeId;
    public int instrumentId;
    public long aggressorOrderId;
    public long passiveOrderId;
    public int side;
    public long price;
    public int priceDecimals;
    public long qty;
    public long aggressorLeaves;
    public long passiveLeaves;
    public long leaves;
    public int requestType;
    public long releaseTime;
    public long raceId;
    public boolean taker;
    public long expiry;
    public long seed;
    public int sessionState;
    public int fixingEventId;
    /** The cancel or replace request this event answers; 0 if none. */
    public long requestId;
    public int lineId;
    public long usageE8;
    public long limitE8;

    private DirectBuffer b;
    private int p;

    private long i64() {
        long v = b.getLong(p, ORDER);
        p += 8;
        return v;
    }

    private int i32() {
        int v = b.getInt(p, ORDER);
        p += 4;
        return v;
    }

    private int i8() {
        return b.getByte(p++);
    }

    /** Decodes the event at {@code offset}; returns its length. */
    public int read(DirectBuffer buffer, int offset) {
        b = buffer;
        int length = buffer.getInt(offset, ORDER);
        type = buffer.getInt(offset + 4, ORDER);
        time = buffer.getLong(offset + 8, ORDER);
        p = offset + 16;
        switch (type) {
            case OutputEncoder.ACCEPTED, OutputEncoder.SUSPENDED -> orderId = i64();
            case OutputEncoder.REJECTED -> {
                orderId = i64();
                reason = i8();
            }
            case OutputEncoder.TRADE -> {
                tradeId = i64();
                instrumentId = i32();
                aggressorOrderId = i64();
                passiveOrderId = i64();
                side = i8();
                price = i64();
                priceDecimals = i32();
                qty = i64();
                aggressorLeaves = i64();
                passiveLeaves = i64();
            }
            case OutputEncoder.CANCELLED -> {
                orderId = i64();
                reason = i8();
                qty = i64();
                requestId = i64();
            }
            case OutputEncoder.REPLACED -> {
                orderId = i64();
                price = i64();
                qty = i64();
                leaves = i64();
                requestId = i64();
            }
            case OutputEncoder.REQUEST_REJECTED -> {
                orderId = i64();
                requestType = i8();
                reason = i8();
                requestId = i64();
            }
            case OutputEncoder.REQUEST_QUEUED -> {
                orderId = i64();
                requestType = i8();
                releaseTime = i64();
                requestId = i64();
            }
            case OutputEncoder.REPRICED -> {
                orderId = i64();
                price = i64();
            }
            case OutputEncoder.FIXING_MATCHED -> {
                tradeId = i64();
                instrumentId = i32();
                fixingEventId = i32();
                aggressorOrderId = i64();
                passiveOrderId = i64();
                side = i8();
                qty = i64();
                aggressorLeaves = i64();
                passiveLeaves = i64();
            }
            case OutputEncoder.RACE_STARTED -> {
                raceId = i64();
                instrumentId = i32();
                taker = i8() != 0;
                side = i8();
                price = i64();
                expiry = i64();
                seed = i64();
            }
            case OutputEncoder.JOINED_RACE -> {
                orderId = i64();
                raceId = i64();
            }
            case OutputEncoder.SESSION_STATE -> {
                instrumentId = i32();
                sessionState = i8();
            }
            case OutputEncoder.END_OF_DAY -> { }
            case OutputEncoder.CREDIT_LIMIT_BREACHED -> {
                lineId = i32();
                usageE8 = i64();
                limitE8 = i64();
            }
            default -> throw new IllegalArgumentException("Unknown output type " + type);
        }
        return length;
    }
}
