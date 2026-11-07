package clob.testkit;

import java.math.BigDecimal;

/**
 * A trade as seen in scenario assertions: which resting order, at what price, for how much. The price
 * is a decimal normalised so that 93 (book trade at 0dp) equals 93.0 (mid trade at 1dp).
 */
public record Fill(long passiveOrderId, BigDecimal price, long qty) {
    public Fill {
        price = price.signum() == 0 ? BigDecimal.ZERO : price.stripTrailingZeros();
    }

    /** A price in whole units of the test instruments (precision 0). */
    public static Fill fill(long passiveOrderId, long price, long qty) {
        return new Fill(passiveOrderId, BigDecimal.valueOf(price), qty);
    }

    /** A decimal price, e.g. {@code "92.5"} for a mid trade between ticks. */
    public static Fill fill(long passiveOrderId, String price, long qty) {
        return new Fill(passiveOrderId, new BigDecimal(price), qty);
    }
}
