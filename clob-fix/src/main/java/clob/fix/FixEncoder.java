package clob.fix;

import java.nio.charset.StandardCharsets;

/**
 * Builds one FIX 4.4 message: standard header (8, 9, 35, 49, 56, 34, 52 and optionally 43/122), the
 * body fields added in order, then BodyLength and CheckSum. Reused per message; the result is
 * {@link #bytes()}[0, length).
 */
final class FixEncoder {
    private final byte[] body = new byte[8192];
    private final byte[] out = new byte[8192 + 64];
    private int bp;
    private int length;

    FixEncoder begin(String msgType, String sender, String target, long seqNum, long epochMillis, boolean possDup,
            long origSendingMillis) {
        bp = 0;
        add(35, msgType);
        add(49, sender);
        add(56, target);
        add(34, seqNum);
        if (possDup) {
            add(43, 'Y');
        }
        addTime(52, epochMillis);
        if (possDup) {
            addTime(122, origSendingMillis);
        }
        return this;
    }

    private void tag(int tag) {
        bp = writeLong(body, bp, tag);
        body[bp++] = '=';
    }

    FixEncoder add(int tag, String value) {
        if (value == null) {
            return this;
        }
        tag(tag);
        for (int i = 0; i < value.length(); i++) {
            body[bp++] = (byte) value.charAt(i);
        }
        body[bp++] = FixParser.SOH;
        return this;
    }

    FixEncoder add(int tag, long value) {
        tag(tag);
        bp = writeLong(body, bp, value);
        body[bp++] = FixParser.SOH;
        return this;
    }

    FixEncoder add(int tag, char value) {
        tag(tag);
        body[bp++] = (byte) value;
        body[bp++] = FixParser.SOH;
        return this;
    }

    /** A fixed-point value with {@code decimals} places, e.g. (108123, 5) → "1.08123". */
    FixEncoder addDecimal(int tag, long value, int decimals) {
        tag(tag);
        if (value < 0) {
            body[bp++] = '-';
            value = -value;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        bp = writeLong(body, bp, value / scale);
        if (decimals > 0) {
            body[bp++] = '.';
            long frac = value % scale;
            for (int i = decimals - 1; i >= 0; i--) {
                body[bp + i] = (byte) ('0' + frac % 10);
                frac /= 10;
            }
            bp += decimals;
        }
        body[bp++] = FixParser.SOH;
        return this;
    }

    FixEncoder addDouble(int tag, double value) {
        return add(tag, java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
    }

    /** Marks the end of the standard header: fields added after this are the resendable body. */
    int mark() {
        return bp;
    }

    /** Copy of the body fields added since {@code mark}, for the resend store. */
    byte[] bodySince(int mark) {
        return java.util.Arrays.copyOfRange(body, mark, bp);
    }

    /** Appends previously encoded body fields (a resend). */
    FixEncoder addRaw(byte[] fields) {
        System.arraycopy(fields, 0, body, bp, fields.length);
        bp += fields.length;
        return this;
    }

    FixEncoder addTime(int tag, long epochMillis) {
        tag(tag);
        FixTime.format(epochMillis, body, bp);
        bp += FixTime.LENGTH;
        body[bp++] = FixParser.SOH;
        return this;
    }

    /** Completes the message. */
    int finish() {
        byte[] begin = "8=FIX.4.4\u00019=".getBytes(StandardCharsets.US_ASCII);
        int p = 0;
        for (byte c : begin) {
            out[p++] = c;
        }
        p = writeLong(out, p, bp);
        out[p++] = FixParser.SOH;
        System.arraycopy(body, 0, out, p, bp);
        p += bp;
        int sum = 0;
        for (int i = 0; i < p; i++) {
            sum += out[i];
        }
        sum &= 0xFF;
        out[p++] = '1';
        out[p++] = '0';
        out[p++] = '=';
        out[p++] = (byte) ('0' + sum / 100);
        out[p++] = (byte) ('0' + sum / 10 % 10);
        out[p++] = (byte) ('0' + sum % 10);
        out[p++] = FixParser.SOH;
        length = p;
        return p;
    }

    byte[] bytes() {
        return out;
    }

    int length() {
        return length;
    }

    private static int writeLong(byte[] b, int p, long v) {
        if (v < 0) {
            b[p++] = '-';
            v = -v;
        }
        if (v == 0) {
            b[p++] = '0';
            return p;
        }
        int start = p;
        while (v > 0) {
            b[p++] = (byte) ('0' + v % 10);
            v /= 10;
        }
        for (int i = start, j = p - 1; i < j; i++, j--) {
            byte t = b[i];
            b[i] = b[j];
            b[j] = t;
        }
        return p;
    }
}
