package clob.refdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Prices;
import org.junit.jupiter.api.Test;

/** The instrument examples from requirements.md. */
class InstrumentTest {

    @Test
    void eurUsdAcceptsOnePipIncrements() {
        Instrument eurusd = Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(5).priceIncrement(1).build();
        assertTrue(eurusd.isValidPrice(Prices.toTicks("1.08123", 5)));
        assertEquals(108123, Prices.toTicks("1.08123", 5));
    }

    @Test
    void usdMxnAcceptsOnlyFivePipIncrements() {
        Instrument usdmxn = Instrument.builder(1, "USD/MXN", "SPOT").pricePrecision(5).priceIncrement(5)
                .mdPriceIncrement(5).build();
        assertTrue(usdmxn.isValidPrice(Prices.toTicks("17.12345", 5)));
        assertFalse(usdmxn.isValidPrice(Prices.toTicks("17.12341", 5)));
    }

    @Test
    void quantityRulesFromRequirements() {
        Instrument i = Instrument.builder(0, "EUR/USD", "SPOT").minQty(1_000_000).qtyIncrement(100_000).minShow(0).build();
        assertTrue(i.isValidQty(1_000_000));
        assertTrue(i.isValidQty(1_100_000));
        assertTrue(i.isValidQty(1_300_000));
        assertFalse(i.isValidQty(1_050_000));
        assertFalse(i.isValidQty(900_000));
        assertTrue(i.isValidShow(0, 5_000_000), "min show 0 allows a fully hidden order");
    }

    @Test
    void showMustBeFillableAndRespectMinShow() {
        Instrument i = Instrument.builder(0, "EUR/USD", "SPOT").minQty(1_000_000).qtyIncrement(100_000)
                .minShow(2_000_000).build();
        assertFalse(i.isValidShow(0, 5_000_000));
        assertFalse(i.isValidShow(1_500_000, 5_000_000));
        assertTrue(i.isValidShow(2_000_000, 5_000_000));
        assertTrue(i.isValidShow(5_000_000, 5_000_000));
    }

    @Test
    void swapPricesMayBeZeroOrNegative() {
        Instrument swap = Instrument.builder(0, "EUR/USD", "1M").type(InstrumentType.SWAP).build();
        assertTrue(swap.isValidPrice(0));
        assertTrue(swap.isValidPrice(-25));
        Instrument spot = Instrument.builder(1, "EUR/USD", "SPOT").build();
        assertFalse(spot.isValidPrice(0));
    }

    @Test
    void rejectsMinQtyOffIncrementGrid() {
        assertThrows(IllegalArgumentException.class,
                () -> Instrument.builder(0, "EUR/USD", "SPOT").minQty(1_050_000).qtyIncrement(100_000).build());
    }

    @Test
    void rejectsMarketDataIncrementNotMultipleOfPriceIncrement() {
        assertThrows(IllegalArgumentException.class,
                () -> Instrument.builder(0, "USD/MXN", "SPOT").priceIncrement(5).mdPriceIncrement(3).build());
    }

    @Test
    void pricesRoundTrip() {
        assertEquals("0.99950", Prices.format(99950, 5));
        assertThrows(IllegalArgumentException.class, () -> Prices.toTicks("1.000001", 5));
    }
}
