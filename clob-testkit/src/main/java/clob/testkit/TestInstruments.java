package clob.testkit;

import clob.refdata.Instrument;
import clob.refdata.MatchPriority;

/** Instruments shaped like the requirements' worked examples: integer prices, quantities in 1000s. */
public final class TestInstruments {
    private TestInstruments() {}

    /** Precision 0, price increment 1, min qty 1000, qty increment 1000, min show 0, no min TTL. */
    public static Instrument.Builder example(MatchPriority priority) {
        return Instrument.builder(0, "TEST/USD", "SPOT")
                .pricePrecision(0)
                .priceIncrement(1)
                .mdPriceIncrement(1)
                .minQty(1_000)
                .qtyIncrement(1_000)
                .maxQty(1_000_000)
                .minShow(0)
                .minTtlNanos(0)
                .matchPriority(priority);
    }

    public static Instrument visibility() {
        return example(MatchPriority.VISIBILITY).build();
    }

    public static Instrument price() {
        return example(MatchPriority.PRICE).build();
    }
}
