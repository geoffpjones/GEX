package clob.sequencer;

import clob.common.Side;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.MassCancelScope;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import clob.engine.SessionState;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.TimeInForce;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Fixed-layout binary encoding of every engine input, little-endian (SBE-style; a generated SBE codec
 * can replace it without changing the layout). Every message starts with a 24-byte header:
 *
 * <pre>
 *   0  int  length (header + body)
 *   4  int  type
 *   8  long sequence  (stamped by the sequencer; 0 before)
 *  16  long time      (epoch nanos, stamped by the sequencer)
 *  24  body
 * </pre>
 *
 * Enums are their stable wire id as a byte ({@link Wire}); null is -1. Gateways encode with sequence
 * and time 0; the sequencer stamps them in place.
 *
 * <p>New orders, cancels and replaces from a FIX gateway end with an identity trailer the engine
 * ignores: {@code byte compIdLength, compId, byte clOrdIdLength, clOrdId, byte ordType}. Journalling it
 * lets recovery rebuild which logical session owns each order and request.
 */
public final class InputCodec {
    public static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    public static final int LENGTH = 0;
    public static final int TYPE = 4;
    public static final int SEQUENCE = 8;
    public static final int TIME = 16;
    public static final int HEADER = 24;
    public static final int MAX_LENGTH = 256;
    /** Longest ClOrdID or SenderCompID journalled with a request. */
    public static final int MAX_ID_LENGTH = 64;

    public static final int NEW_ORDER = 1;
    public static final int CANCEL = 2;
    public static final int REPLACE = 3;
    public static final int MASS_CANCEL = 4;
    public static final int TIME_ADVANCE = 5;
    public static final int END_OF_DAY = 6;
    public static final int CREDIT_LINE = 7;
    public static final int CREDIT_RELATIONSHIP = 8;
    public static final int USD_RATE = 9;
    public static final int VALUE_DATES = 10;
    public static final int VALUE_DATE_ROLL = 11;
    public static final int FIXING_SCHEDULE = 12;
    public static final int FIXING_RATE = 13;
    public static final int SESSION_STATE = 14;
    /** Market data cycle boundary; body: the boundary instant. The same cut for every consumer. */
    public static final int MD_TICK = 15;

    private static final int CURRENCY_BYTES = 8;

    private InputCodec() {}

    // ------------------------------------------------------------------ header

    public static int length(DirectBuffer b, int o) {
        return b.getInt(o + LENGTH, ORDER);
    }

    public static int type(DirectBuffer b, int o) {
        return b.getInt(o + TYPE, ORDER);
    }

    public static long sequence(DirectBuffer b, int o) {
        return b.getLong(o + SEQUENCE, ORDER);
    }

    public static long time(DirectBuffer b, int o) {
        return b.getLong(o + TIME, ORDER);
    }

    public static void stamp(MutableDirectBuffer b, int o, long sequence, long time) {
        b.putLong(o + SEQUENCE, sequence, ORDER);
        b.putLong(o + TIME, time, ORDER);
    }

    private static int header(MutableDirectBuffer b, int o, int type, int bodyLength) {
        int length = HEADER + bodyLength;
        b.putInt(o + LENGTH, length, ORDER);
        b.putInt(o + TYPE, type, ORDER);
        b.putLong(o + SEQUENCE, 0, ORDER);
        b.putLong(o + TIME, 0, ORDER);
        return length;
    }

    /**
     * Appends the identity trailer to an encoded message and returns the new length. {@code ordType} is
     * the FIX OrdType as sent (0 for cancels and replaces).
     */
    public static int withIdentity(MutableDirectBuffer b, int o, int length, byte[] compId, byte[] clOrdId, byte ordType) {
        if (compId.length > MAX_ID_LENGTH || clOrdId.length > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("identifier longer than " + MAX_ID_LENGTH);
        }
        int p = o + length;
        b.putByte(p++, (byte) compId.length);
        b.putBytes(p, compId);
        p += compId.length;
        b.putByte(p++, (byte) clOrdId.length);
        b.putBytes(p, clOrdId);
        p += clOrdId.length;
        b.putByte(p++, ordType);
        int total = p - o;
        b.putInt(o + LENGTH, total, ORDER);
        return total;
    }

    /** Body length of a message type without its identity trailer, or -1 if it cannot carry one. */
    private static int bodyLength(int type) {
        return switch (type) {
            case NEW_ORDER -> 68;
            case CANCEL -> 21;
            case REPLACE -> 52;
            default -> -1;
        };
    }

    /** Offset of the identity trailer, or -1 if the message has none. */
    public static int identityOffset(DirectBuffer b, int o) {
        int body = bodyLength(type(b, o));
        return body < 0 || length(b, o) <= HEADER + body ? -1 : o + HEADER + body;
    }

    public static String identityCompId(DirectBuffer b, int at) {
        return ascii(b, at + 1, b.getByte(at));
    }

    public static String identityClOrdId(DirectBuffer b, int at) {
        int comp = b.getByte(at);
        return ascii(b, at + 2 + comp, b.getByte(at + 1 + comp));
    }

    public static byte identityOrdType(DirectBuffer b, int at) {
        int comp = b.getByte(at);
        int cl = b.getByte(at + 1 + comp);
        return b.getByte(at + 2 + comp + cl);
    }

    private static String ascii(DirectBuffer b, int at, int length) {
        byte[] out = new byte[length];
        b.getBytes(at, out);
        return new String(out, StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ encoders (return the length)

    public static int newOrder(MutableDirectBuffer b, int o, NewOrderCommand c) {
        int p = o + HEADER;
        b.putLong(p, c.orderId, ORDER);
        b.putInt(p + 8, c.instrumentId, ORDER);
        b.putInt(p + 12, c.accountId, ORDER);
        b.putInt(p + 16, c.userId, ORDER);
        b.putByte(p + 20, Wire.SIDES.id(c.side));
        b.putByte(p + 21, Wire.ORDER_TYPES.id(c.type));
        b.putByte(p + 22, Wire.TIFS.id(c.tif));
        b.putByte(p + 23, Wire.PEG_REFERENCES.id(c.pegReference));
        b.putLong(p + 24, c.price, ORDER);
        b.putLong(p + 32, c.qty, ORDER);
        b.putLong(p + 40, c.maxShow, ORDER);
        b.putLong(p + 48, c.expireTime, ORDER);
        b.putLong(p + 56, c.discretion, ORDER);
        b.putInt(p + 64, c.fixingEventId, ORDER);
        return header(b, o, NEW_ORDER, 68);
    }

    public static void decodeNewOrder(DirectBuffer b, int o, NewOrderCommand c) {
        int p = o + HEADER;
        c.orderId = b.getLong(p, ORDER);
        c.instrumentId = b.getInt(p + 8, ORDER);
        c.accountId = b.getInt(p + 12, ORDER);
        c.userId = b.getInt(p + 16, ORDER);
        c.side = Wire.SIDES.of(b.getByte(p + 20));
        c.type = Wire.ORDER_TYPES.of(b.getByte(p + 21));
        c.tif = Wire.TIFS.of(b.getByte(p + 22));
        c.pegReference = Wire.PEG_REFERENCES.of(b.getByte(p + 23));
        c.price = b.getLong(p + 24, ORDER);
        c.qty = b.getLong(p + 32, ORDER);
        c.maxShow = b.getLong(p + 40, ORDER);
        c.expireTime = b.getLong(p + 48, ORDER);
        c.discretion = b.getLong(p + 56, ORDER);
        c.fixingEventId = b.getInt(p + 64, ORDER);
    }

    public static int cancel(MutableDirectBuffer b, int o, long orderId, int userId, CancelSource source) {
        return cancel(b, o, orderId, userId, source, 0);
    }

    public static int cancel(MutableDirectBuffer b, int o, long orderId, int userId, CancelSource source, long requestId) {
        int p = o + HEADER;
        b.putLong(p, orderId, ORDER);
        b.putInt(p + 8, userId, ORDER);
        b.putByte(p + 12, Wire.CANCEL_SOURCES.id(source));
        b.putLong(p + 13, requestId, ORDER);
        return header(b, o, CANCEL, 21);
    }

    public static long cancelRequestId(DirectBuffer b, int o) {
        return b.getLong(o + HEADER + 13, ORDER);
    }

    public static long cancelOrderId(DirectBuffer b, int o) {
        return b.getLong(o + HEADER, ORDER);
    }

    public static int cancelUserId(DirectBuffer b, int o) {
        return b.getInt(o + HEADER + 8, ORDER);
    }

    public static CancelSource cancelSource(DirectBuffer b, int o) {
        return Wire.CANCEL_SOURCES.of(b.getByte(o + HEADER + 12));
    }

    public static int replace(MutableDirectBuffer b, int o, ReplaceCommand c) {
        int p = o + HEADER;
        b.putLong(p, c.orderId, ORDER);
        b.putInt(p + 8, c.userId, ORDER);
        b.putLong(p + 12, c.price, ORDER);
        b.putLong(p + 20, c.qty, ORDER);
        b.putLong(p + 28, c.maxShow, ORDER);
        b.putLong(p + 36, c.discretion, ORDER);
        b.putLong(p + 44, c.requestId, ORDER);
        return header(b, o, REPLACE, 52);
    }

    public static void decodeReplace(DirectBuffer b, int o, ReplaceCommand c) {
        int p = o + HEADER;
        c.orderId = b.getLong(p, ORDER);
        c.userId = b.getInt(p + 8, ORDER);
        c.price = b.getLong(p + 12, ORDER);
        c.qty = b.getLong(p + 20, ORDER);
        c.maxShow = b.getLong(p + 28, ORDER);
        c.discretion = b.getLong(p + 36, ORDER);
        c.requestId = b.getLong(p + 44, ORDER);
    }

    public static int massCancel(MutableDirectBuffer b, int o, MassCancelScope scope, int scopeId, int userId,
            CancelSource source) {
        int p = o + HEADER;
        b.putByte(p, Wire.SCOPES.id(scope));
        b.putInt(p + 1, scopeId, ORDER);
        b.putInt(p + 5, userId, ORDER);
        b.putByte(p + 9, Wire.CANCEL_SOURCES.id(source));
        return header(b, o, MASS_CANCEL, 10);
    }

    public static int timeAdvance(MutableDirectBuffer b, int o) {
        return header(b, o, TIME_ADVANCE, 0);
    }

    public static int endOfDay(MutableDirectBuffer b, int o) {
        return header(b, o, END_OF_DAY, 0);
    }

    public static int creditLine(MutableDirectBuffer b, int o, int lineId, int giver, CreditType type, long limitUsd) {
        int p = o + HEADER;
        b.putInt(p, lineId, ORDER);
        b.putInt(p + 4, giver, ORDER);
        b.putByte(p + 8, Wire.CREDIT_TYPES.id(type));
        b.putLong(p + 9, limitUsd, ORDER);
        return header(b, o, CREDIT_LINE, 17);
    }

    public static int creditRelationship(MutableDirectBuffer b, int o, int from, int to, int instrumentId, int lineId) {
        int p = o + HEADER;
        b.putInt(p, from, ORDER);
        b.putInt(p + 4, to, ORDER);
        b.putInt(p + 8, instrumentId, ORDER);
        b.putInt(p + 12, lineId, ORDER);
        return header(b, o, CREDIT_RELATIONSHIP, 16);
    }

    public static int usdRate(MutableDirectBuffer b, int o, String currency, long rateE8) {
        byte[] code = currency.getBytes(StandardCharsets.US_ASCII);
        if (code.length > CURRENCY_BYTES) {
            throw new IllegalArgumentException("Currency code longer than " + CURRENCY_BYTES + ": " + currency);
        }
        int p = o + HEADER;
        for (int i = 0; i < CURRENCY_BYTES; i++) {
            b.putByte(p + i, i < code.length ? code[i] : 0);
        }
        b.putLong(p + CURRENCY_BYTES, rateE8, ORDER);
        return header(b, o, USD_RATE, CURRENCY_BYTES + 8);
    }

    public static String usdRateCurrency(DirectBuffer b, int o) {
        int p = o + HEADER;
        int n = 0;
        while (n < CURRENCY_BYTES && b.getByte(p + n) != 0) {
            n++;
        }
        byte[] code = new byte[n];
        b.getBytes(p, code);
        return new String(code, StandardCharsets.US_ASCII);
    }

    public static int valueDates(MutableDirectBuffer b, int o, int instrumentId, int near, int far) {
        int p = o + HEADER;
        b.putInt(p, instrumentId, ORDER);
        b.putInt(p + 4, near, ORDER);
        b.putInt(p + 8, far, ORDER);
        return header(b, o, VALUE_DATES, 12);
    }

    public static int valueDateRoll(MutableDirectBuffer b, int o, int today) {
        b.putInt(o + HEADER, today, ORDER);
        return header(b, o, VALUE_DATE_ROLL, 4);
    }

    public static int fixingSchedule(MutableDirectBuffer b, int o, int eventId, long cutoff, long fixingTime) {
        int p = o + HEADER;
        b.putInt(p, eventId, ORDER);
        b.putLong(p + 4, cutoff, ORDER);
        b.putLong(p + 12, fixingTime, ORDER);
        return header(b, o, FIXING_SCHEDULE, 20);
    }

    public static int fixingRate(MutableDirectBuffer b, int o, int eventId, int instrumentId, long rate) {
        int p = o + HEADER;
        b.putInt(p, eventId, ORDER);
        b.putInt(p + 4, instrumentId, ORDER);
        b.putLong(p + 8, rate, ORDER);
        return header(b, o, FIXING_RATE, 16);
    }

    public static int sessionState(MutableDirectBuffer b, int o, int instrumentId, SessionState state) {
        int p = o + HEADER;
        b.putInt(p, instrumentId, ORDER);
        b.putByte(p + 4, Wire.SESSION_STATES.id(state));
        return header(b, o, SESSION_STATE, 5);
    }

    public static int mdTick(MutableDirectBuffer b, int o, long boundary) {
        b.putLong(o + HEADER, boundary, ORDER);
        return header(b, o, MD_TICK, 8);
    }

    public static long mdTickBoundary(DirectBuffer b, int o) {
        return b.getLong(o + HEADER, ORDER);
    }

    // ------------------------------------------------------------------ body field readers (used by the dispatcher)

    static int i32(DirectBuffer b, int o, int bodyOffset) {
        return b.getInt(o + HEADER + bodyOffset, ORDER);
    }

    static long i64(DirectBuffer b, int o, int bodyOffset) {
        return b.getLong(o + HEADER + bodyOffset, ORDER);
    }

    static byte i8(DirectBuffer b, int o, int bodyOffset) {
        return b.getByte(o + HEADER + bodyOffset);
    }
}
