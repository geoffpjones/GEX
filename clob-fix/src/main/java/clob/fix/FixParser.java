package clob.fix;

import java.nio.charset.StandardCharsets;

/**
 * Zero-copy FIX tag=value parser over a byte array. {@link #frame} finds complete messages in a
 * stream; {@link #parse} indexes the fields of one message and validates BodyLength and CheckSum.
 */
final class FixParser {
    static final byte SOH = 1;
    private static final byte[] BEGIN = "8=FIX.4.4\u00019=".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FIELDS = 256;
    /** Largest message accepted: the connection's whole read buffer. */
    static final int MAX_MESSAGE = 1 << 16;

    private final int[] tags = new int[MAX_FIELDS];
    private final int[] valueStart = new int[MAX_FIELDS];
    private final int[] valueLength = new int[MAX_FIELDS];
    private byte[] buf;
    private int count;

    /**
     * Length of the complete message at {@code off}, 0 if more bytes are needed, -1 if the bytes are not a
     * FIX 4.4 message.
     */
    static int frame(byte[] b, int off, int len) {
        int n = Math.min(len, BEGIN.length);
        for (int i = 0; i < n; i++) {
            if (b[off + i] != BEGIN[i]) {
                return -1;
            }
        }
        if (len < BEGIN.length) {
            return 0;
        }
        int p = off + BEGIN.length;
        int bodyLength = 0;
        int digits = 0;
        while (p < off + len && b[p] != SOH) {
            if (b[p] < '0' || b[p] > '9' || ++digits > 7) {
                return -1;
            }
            bodyLength = bodyLength * 10 + (b[p] - '0');
            p++;
        }
        if (p >= off + len) {
            return 0;
        }
        if (digits == 0) {
            return -1;
        }
        int total = (p + 1 - off) + bodyLength + 7; // "10=NNN<SOH>"
        if (total > MAX_MESSAGE) {
            return -1;
        }
        return len >= total ? total : 0;
    }

    /** Indexes one framed message. Returns null if valid, otherwise a reason. */
    String parse(byte[] b, int off, int length) {
        buf = b;
        count = 0;
        int p = off;
        int end = off + length;
        int bodyStart = -1;
        int checksumStart = -1;
        while (p < end) {
            int tag = 0;
            while (p < end && b[p] != '=') {
                if (b[p] < '0' || b[p] > '9' || tag > 99_999_999) {
                    return "bad tag";
                }
                tag = tag * 10 + (b[p] - '0');
                p++;
            }
            if (p >= end) {
                return "truncated field";
            }
            int vs = ++p;
            while (p < end && b[p] != SOH) {
                p++;
            }
            if (p >= end) {
                return "missing SOH";
            }
            if (count == MAX_FIELDS) {
                return "too many fields";
            }
            tags[count] = tag;
            valueStart[count] = vs;
            valueLength[count] = p - vs;
            count++;
            p++;
            if (tag == 9) {
                bodyStart = p;
            } else if (tag == 10) {
                checksumStart = vs - 3;
            }
        }
        if (count < 4 || tags[0] != 8 || tags[1] != 9 || tags[2] != 35 || tags[count - 1] != 10) {
            return "bad header or trailer";
        }
        long declared;
        long declaredSum;
        try {
            declared = asLong(9);
            declaredSum = asLong(10);
        } catch (NumberFormatException e) {
            return "bad BodyLength or CheckSum";
        }
        if (checksumStart - bodyStart != declared) {
            return "bad BodyLength";
        }
        int sum = 0;
        for (int i = off; i < checksumStart; i++) {
            sum += b[i];
        }
        if ((sum & 0xFF) != declaredSum) {
            return "bad CheckSum";
        }
        return null;
    }

    int find(int tag) {
        for (int i = 0; i < count; i++) {
            if (tags[i] == tag) {
                return i;
            }
        }
        return -1;
    }

    boolean has(int tag) {
        return find(tag) >= 0;
    }

    String str(int tag) {
        int i = find(tag);
        return i < 0 ? null : new String(buf, valueStart[i], valueLength[i], StandardCharsets.US_ASCII);
    }

    char asChar(int tag) {
        int i = find(tag);
        return i < 0 || valueLength[i] == 0 ? 0 : (char) buf[valueStart[i]];
    }

    boolean is(int tag, String value) {
        int i = find(tag);
        if (i < 0 || valueLength[i] != value.length()) {
            return false;
        }
        for (int k = 0; k < valueLength[i]; k++) {
            if (buf[valueStart[i] + k] != value.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** Integer value; throws NumberFormatException if absent or not an integer. */
    long asLong(int tag) {
        int i = find(tag);
        if (i < 0 || valueLength[i] == 0) {
            throw new NumberFormatException("missing tag " + tag);
        }
        int p = valueStart[i];
        int end = p + valueLength[i];
        boolean neg = buf[p] == '-';
        if (neg) {
            p++;
        }
        if (p == end) {
            throw new NumberFormatException("tag " + tag + " has no digits");
        }
        long v = 0;
        try {
            for (; p < end; p++) {
                if (buf[p] < '0' || buf[p] > '9') {
                    throw new NumberFormatException("tag " + tag + " is not an integer");
                }
                v = Math.addExact(Math.multiplyExact(v, 10), buf[p] - '0');
            }
        } catch (ArithmeticException e) {
            throw new NumberFormatException("tag " + tag + " is out of range");
        }
        return neg ? -v : v;
    }

    /**
     * Decimal value scaled to {@code decimals} places (e.g. "1.08123" at 5 → 108123). Throws
     * NumberFormatException if absent, malformed, or more precise than {@code decimals}.
     */
    long decimal(int tag, int decimals) {
        int i = find(tag);
        if (i < 0 || valueLength[i] == 0) {
            throw new NumberFormatException("missing tag " + tag);
        }
        int p = valueStart[i];
        int end = p + valueLength[i];
        boolean neg = buf[p] == '-';
        if (neg) {
            p++;
        }
        long v = 0;
        int frac = -1;
        int digits = 0;
        try {
        for (; p < end; p++) {
            byte c = buf[p];
            if (c == '.') {
                if (frac >= 0) {
                    throw new NumberFormatException("tag " + tag + " has two decimal points");
                }
                frac = 0;
                continue;
            }
            if (c < '0' || c > '9') {
                throw new NumberFormatException("tag " + tag + " is not a decimal");
            }
            digits++;
            if (frac >= 0) {
                frac++;
                if (frac > decimals) {
                    if (c != '0') {
                        throw new NumberFormatException("tag " + tag + " has more than " + decimals + " decimals");
                    }
                    continue;
                }
            }
            v = Math.addExact(Math.multiplyExact(v, 10), c - '0');
        }
        if (digits == 0) {
            throw new NumberFormatException("tag " + tag + " has no digits");
        }
        for (int k = Math.max(frac, 0); k < decimals; k++) {
            v = Math.multiplyExact(v, 10);
        }
        } catch (ArithmeticException e) {
            throw new NumberFormatException("tag " + tag + " is out of range");
        }
        return neg ? -v : v;
    }

    int fieldCount() {
        return count;
    }
}
