package clob.credit;

import static clob.credit.CreditEngine.ALL_INSTRUMENTS;
import static clob.credit.CreditEngine.NO_LINE;
import static clob.credit.CreditEngine.RATE_SCALE;
import static clob.credit.CreditEngine.UNLIMITED_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.RefData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreditEngineTest {
    static final int EURUSD = 0;
    static final int USDTRY = 1;
    static final int USDPLN = 2;
    static final int EURUSD_1M = 3;
    static final int TODAY = 20_000;
    static final int FAR = 20_030;

    CreditEngine c;

    @BeforeEach
    void setUp() {
        RefData refData = RefData.builder()
                .add(Instrument.builder(EURUSD, "EUR/USD", "SPOT").build())
                .add(Instrument.builder(USDTRY, "USD/TRY", "SPOT").build())
                .add(Instrument.builder(USDPLN, "USD/PLN", "SPOT").build())
                .add(Instrument.builder(EURUSD_1M, "EUR/USD", "1M").type(InstrumentType.SWAP).build())
                .build();
        c = new CreditEngine(10, refData);
        c.setUsdRate("EUR", 110_000_000L); // 1.10
        c.setUsdRate("USD", RATE_SCALE);
        for (int i = 0; i < 3; i++) {
            c.setValueDates(i, TODAY, -1);
        }
        c.setValueDates(EURUSD_1M, TODAY, FAR);
    }

    private void unlimited(int lineId, int from, int to) {
        c.setLine(lineId, from, CreditType.UNLIMITED, 0);
        c.setRelationship(from, to, ALL_INSTRUMENTS, lineId);
    }

    @Test
    void eligibilityIsBilateral() {
        unlimited(1, 1, 2);
        assertFalse(c.eligible(1, 2, EURUSD));
        assertEquals(0, c.capacity(1, 2, EURUSD, Side.BUY));
        unlimited(2, 2, 1);
        assertTrue(c.eligible(1, 2, EURUSD));
        assertEquals(UNLIMITED_CAPACITY, c.capacity(1, 2, EURUSD, Side.BUY));
    }

    @Test
    void requirementsExampleUnlimitedExceptThreeInstrumentsOnDsl() {
        // "unlimited credit ... for all instruments except USD/TRY, USD/ILS and USD/PLN which have 100mio DSL"
        unlimited(1, 1, 2);
        unlimited(2, 2, 1);
        c.setLine(3, 1, CreditType.DSL, 100_000_000L); // one pool shared by the exception instruments
        c.setRelationship(1, 2, USDTRY, 3);
        c.setRelationship(1, 2, USDPLN, 3);
        assertEquals(UNLIMITED_CAPACITY, c.capacity(1, 2, EURUSD, Side.BUY));
        assertEquals(100_000_000L, c.capacity(1, 2, USDTRY, Side.BUY));
        c.commit(1, 2, USDTRY, Side.BUY, 60_000_000L);
        assertEquals(40_000_000L, c.capacity(1, 2, USDPLN, Side.SELL), "pool is shared across instruments");
    }

    @Test
    void perInstrumentNoCreditOverride() {
        unlimited(1, 1, 2);
        unlimited(2, 2, 1);
        c.setRelationship(2, 1, USDTRY, NO_LINE);
        assertTrue(c.eligible(1, 2, EURUSD));
        assertFalse(c.eligible(1, 2, USDTRY));
    }

    @Test
    void dslConvertsToUsdAndIsPerValueDate() {
        c.setLine(1, 1, CreditType.DSL, 11_000_000L);
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        unlimited(2, 2, 1);
        assertEquals(10_000_000L, c.capacity(1, 2, EURUSD, Side.BUY), "USD 11m at 1.10 = EUR 10m");
        c.commit(1, 2, EURUSD, Side.BUY, 4_000_000L);
        assertEquals(6_000_000L, c.capacity(1, 2, EURUSD, Side.SELL), "DSL is gross: either direction consumes");
        assertEquals(4_400_000L * RATE_SCALE, c.dslUsageE8(1, TODAY));
        c.setValueDates(EURUSD, TODAY + 1, -1);
        assertEquals(10_000_000L, c.capacity(1, 2, EURUSD, Side.BUY), "a new value date has its own limit");
        c.rollValueDate(TODAY + 1);
        assertEquals(0, c.dslUsageE8(1, TODAY), "settled date released");
    }

    @Test
    void nopNetsAndAllowsReducingTrades() {
        c.setLine(1, 1, CreditType.NOP, 3_000_000L);
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        unlimited(2, 2, 1);
        assertEquals(3_000_000L, c.capacity(1, 2, USDTRY, Side.BUY));
        c.commit(1, 2, USDTRY, Side.BUY, 3_000_000L);
        assertEquals(0, c.capacity(1, 2, USDTRY, Side.BUY));
        assertEquals(6_000_000L, c.capacity(1, 2, USDTRY, Side.SELL), "selling reduces, then may go short to the limit");
        c.setLine(1, 1, CreditType.NOP, 1_000_000L); // limit cut below usage
        assertEquals(0, c.capacity(1, 2, USDTRY, Side.BUY));
        assertEquals(4_000_000L, c.capacity(1, 2, USDTRY, Side.SELL));
        assertFalse(c.withinLimits());
    }

    @Test
    void nopIsMeasuredFromEachGiversSide() {
        unlimited(1, 1, 2);
        c.setLine(2, 2, CreditType.NOP, 2_000_000L);
        c.setRelationship(2, 1, ALL_INSTRUMENTS, 2);
        // Account 1 sells to 2, so 2 buys: 2's position against 1 goes long.
        assertEquals(2_000_000L, c.capacity(1, 2, USDTRY, Side.SELL));
        c.commit(1, 2, USDTRY, Side.SELL, 2_000_000L);
        assertEquals(2_000_000L * RATE_SCALE, c.nopE8(2));
        assertEquals(0, c.capacity(1, 2, USDTRY, Side.SELL));
    }

    @Test
    void swapConsumesDslOnBothLegsButNetsForNop() {
        c.setLine(1, 1, CreditType.DSL, 3_300_000L);
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        c.setLine(2, 2, CreditType.NOP, 1L);
        c.setRelationship(2, 1, ALL_INSTRUMENTS, 2);
        assertEquals(3_000_000L, c.capacity(1, 2, EURUSD_1M, Side.BUY), "NOP does not bind: legs net to zero");
        c.commit(1, 2, EURUSD_1M, Side.BUY, 2_000_000L);
        assertEquals(2_200_000L * RATE_SCALE, c.dslUsageE8(1, TODAY));
        assertEquals(2_200_000L * RATE_SCALE, c.dslUsageE8(1, FAR));
        assertEquals(0, c.nopE8(2));
        c.commit(1, 2, EURUSD, Side.BUY, 500_000L); // spot usage on the near date only
        assertEquals(500_000L, c.capacity(1, 2, EURUSD_1M, Side.BUY), "the tighter leg date binds");
    }

    @Test
    void missingRateBlocksLimitedLinesOnly() {
        RefData refData = RefData.builder().add(Instrument.builder(0, "GBP/USD", "SPOT").build()).build();
        CreditEngine e = new CreditEngine(4, refData);
        e.setValueDates(0, TODAY, -1);
        e.setLine(1, 1, CreditType.DSL, 1_000_000L);
        e.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        e.setLine(2, 2, CreditType.UNLIMITED, 0);
        e.setRelationship(2, 1, ALL_INSTRUMENTS, 2);
        e.setRelationship(2, 3, ALL_INSTRUMENTS, 2);
        e.setLine(3, 3, CreditType.UNLIMITED, 0);
        e.setRelationship(3, 2, ALL_INSTRUMENTS, 3);
        assertEquals(0, e.capacity(1, 2, 0, Side.BUY));
        assertEquals(UNLIMITED_CAPACITY, e.capacity(3, 2, 0, Side.BUY));
    }

    @Test
    void lineBelongsToItsGiver() {
        c.setLine(1, 1, CreditType.UNLIMITED, 0);
        assertThrows(IllegalArgumentException.class, () -> c.setRelationship(2, 1, ALL_INSTRUMENTS, 1));
        assertThrows(IllegalArgumentException.class, () -> c.setLine(1, 2, CreditType.DSL, 5));
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        c.setRelationship(1, 3, ALL_INSTRUMENTS, 1); // one giver, many counterparties: allowed
    }

    @Test
    void poolSharedAcrossCounterparties() {
        c.setLine(1, 1, CreditType.DSL, 5_000_000L);
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        c.setRelationship(1, 3, ALL_INSTRUMENTS, 1);
        unlimited(2, 2, 1);
        unlimited(3, 3, 1);
        c.commit(1, 2, USDTRY, Side.BUY, 4_000_000L);
        assertEquals(1_000_000L, c.capacity(1, 3, USDTRY, Side.BUY));
    }

    @Test
    void simulationOverlayIsTemporary() {
        c.setLine(1, 1, CreditType.DSL, 5_000_000L);
        c.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        unlimited(2, 2, 1);
        c.beginSimulation();
        c.commit(1, 2, USDTRY, Side.BUY, 3_000_000L);
        assertEquals(2_000_000L, c.capacity(1, 2, USDTRY, Side.BUY));
        assertEquals(0, c.dslUsageE8(1, TODAY), "nothing real recorded");
        c.endSimulation();
        assertEquals(5_000_000L, c.capacity(1, 2, USDTRY, Side.BUY));
    }

    @Test
    void validatesInputs() {
        assertThrows(IllegalArgumentException.class, () -> c.setLine(1, 1, CreditType.DSL, CreditEngine.MAX_LIMIT_USD + 1));
        assertThrows(IllegalArgumentException.class, () -> c.setUsdRate("JPY", RATE_SCALE));
        assertThrows(IllegalArgumentException.class, () -> c.setUsdRate("EUR", 0));
        assertThrows(IllegalArgumentException.class, () -> c.setRelationship(1, 2, ALL_INSTRUMENTS, 99));
        assertThrows(IllegalArgumentException.class, () -> c.setRelationship(0, 2, ALL_INSTRUMENTS, NO_LINE));
    }
}
