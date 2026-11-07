package clob.common;

import java.math.BigDecimal;

/**
 * Conversion between decimal price strings and fixed-point {@code long} ticks.
 *
 * <p>A tick ("pip" in this spec) is one unit of the instrument's price precision, so EUR/USD at
 * 5dp stores 1.08123 as 108123. Not for the hot path: use at the edges (config, FIX parsing).
 */
public final class Prices {
    private Prices() {}

    public static long toTicks(String value, int precision) {
        BigDecimal scaled = new BigDecimal(value).movePointRight(precision);
        try {
            return scaled.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Price " + value + " is not representable at " + precision + " decimal places", e);
        }
    }

    public static String format(long ticks, int precision) {
        return BigDecimal.valueOf(ticks, precision).toPlainString();
    }
}
