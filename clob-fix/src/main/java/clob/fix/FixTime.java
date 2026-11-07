package clob.fix;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** FIX UTCTimestamp ("yyyyMMdd-HH:mm:ss.SSS") formatting and parsing. */
final class FixTime {
    private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss[.SSS][.SSSSSS][.SSSSSSSSS]");

    private FixTime() {}

    /** Writes the timestamp for {@code epochMillis} into {@code out} (21 bytes). */
    static void format(long epochMillis, byte[] out, int off) {
        long days = Math.floorDiv(epochMillis, 86_400_000L);
        long ms = Math.floorMod(epochMillis, 86_400_000L);
        // civil-from-days (Howard Hinnant), allocation-free
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) {
            y++;
        }
        digits(out, off, y, 4);
        digits(out, off + 4, m, 2);
        digits(out, off + 6, d, 2);
        out[off + 8] = '-';
        digits(out, off + 9, ms / 3_600_000, 2);
        out[off + 11] = ':';
        digits(out, off + 12, ms / 60_000 % 60, 2);
        out[off + 14] = ':';
        digits(out, off + 15, ms / 1000 % 60, 2);
        out[off + 17] = '.';
        digits(out, off + 18, ms % 1000, 3);
    }

    static final int LENGTH = 21;

    private static void digits(byte[] out, int off, long v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            out[off + i] = (byte) ('0' + v % 10);
            v /= 10;
        }
    }

    /** Epoch nanos for a UTCTimestamp string. */
    static long parseNanos(String s) {
        LocalDateTime t = LocalDateTime.parse(s, PARSE);
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + t.getNano();
    }
}
