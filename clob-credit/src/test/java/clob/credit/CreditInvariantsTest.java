package clob.credit;

import static clob.credit.CreditEngine.ALL_INSTRUMENTS;
import static clob.credit.CreditEngine.RATE_SCALE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.RefData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R06, R07, R10 and D1a from review.md. */
class CreditInvariantsTest {
    static final int SPOT = 0;
    static final int SWAP = 1;
    static final int TODAY = 20_000;
    static final int FAR = 20_030;
    static final CreditEngine.BreachListener NO_BREACH = (l, u, m) -> { throw new AssertionError("no breach expected"); };

    CreditEngine c;

    @BeforeEach
    void setUp() {
        RefData refData = RefData.builder().add(Instrument.builder(SPOT, "EUR/USD", "SPOT").build())
                .add(Instrument.builder(SWAP, "EUR/USD", "1M").type(InstrumentType.SWAP).build()).build();
        c = new CreditEngine(10, refData);
        c.setUsdRate("EUR", RATE_SCALE);
    }

    private void bilateral(int lineA, int a, int lineB, int b, CreditType type, long limit) {
        assertNull(c.setLine(lineA, a, type, limit));
        assertNull(c.setLine(lineB, b, type, limit));
        assertNull(c.setRelationship(a, b, ALL_INSTRUMENTS, lineA));
        assertNull(c.setRelationship(b, a, ALL_INSTRUMENTS, lineB));
    }

    @Test
    void nopWithoutValueDateHasNoCapacity() {
        bilateral(1, 1, 2, 2, CreditType.NOP, 10_000);
        assertEquals(0, c.capacity(1, 2, SPOT, Side.BUY), "a NOP trade without a value date could never be settled");
        assertFalse(c.hasRequiredData(SPOT));
        assertThrows(IllegalStateException.class, () -> c.commit(1, 2, SPOT, Side.BUY, 1));
        assertEquals(0, c.nopE8(1));
    }

    @Test
    void rollCannotEraseExposureAndStaleDatesBlock() {
        bilateral(1, 1, 2, 2, CreditType.NOP, 10_000);
        assertNull(c.setValueDates(SPOT, TODAY, -1));
        c.commit(1, 2, SPOT, Side.BUY, 1_000);
        assertNull(c.rollValueDate(TODAY, NO_BREACH));
        assertEquals(1_000 * RATE_SCALE, c.nopE8(1), "a date not yet settled keeps its exposure");
        assertNull(c.rollValueDate(TODAY + 1, NO_BREACH));
        assertEquals(0, c.nopE8(1), "settled once the date has passed");
        assertEquals(0, c.capacity(1, 2, SPOT, Side.BUY), "the value date is now in the past");
        assertFalse(c.hasRequiredData(SPOT));
        assertEquals(CreditRefusal.VALUE_DATE_BEFORE_BUSINESS_DATE, c.setValueDates(SPOT, TODAY, -1));
        assertEquals(CreditRefusal.BUSINESS_DATE_WENT_BACKWARDS, c.rollValueDate(TODAY, NO_BREACH));
    }

    @Test
    void grossTurnoverCannotOverflowAndBothSidesCommitTogether() {
        long limit = CreditEngine.MAX_LIMIT_USD;
        bilateral(1, 1, 2, 2, CreditType.NOP, limit);
        bilateral(3, 3, 4, 2, CreditType.NOP, limit); // account 2's second line is unused history-free
        assertNull(c.setValueDates(SPOT, TODAY, -1));
        for (int i = 0; i < 50; i++) {
            Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
            long qty = i == 0 ? limit : 2 * limit;
            assertEquals(qty, Math.min(qty, c.capacity(1, 2, SPOT, side)));
            c.commit(1, 2, SPOT, side, qty);
        }
        assertTrue(c.withinLimits());
        // A trade for a fresh account still works after heavy turnover on its counterparty.
        c.commit(3, 2, SPOT, Side.BUY, limit);
        assertEquals(limit * RATE_SCALE, c.nopE8(3));
        assertEquals(-limit * RATE_SCALE, c.nopE8(4));
    }

    @Test
    void perDateBucketsAreBoundedAcrossAlternatingDates() {
        RefData refData = RefData.builder().add(Instrument.builder(0, "EUR/USD", "SPOT").build())
                .add(Instrument.builder(1, "EUR/USD", "TN").build()).build();
        CreditEngine e = new CreditEngine(4, refData);
        e.setUsdRate("EUR", RATE_SCALE);
        e.setLine(1, 1, CreditType.NOP, CreditEngine.MAX_LIMIT_USD);
        e.setLine(2, 2, CreditType.UNLIMITED, 0);
        e.setRelationship(1, 2, ALL_INSTRUMENTS, 1);
        e.setRelationship(2, 1, ALL_INSTRUMENTS, 2);
        e.setValueDates(0, TODAY, -1);
        e.setValueDates(1, TODAY + 1, -1);
        long limit = CreditEngine.MAX_LIMIT_USD;
        // Long one date, short the other, repeatedly: the net stays in limit but each date's bucket grows.
        for (int i = 0; i < 100; i++) {
            int instrument = i % 2;
            Side side = instrument == 0 ? Side.BUY : Side.SELL;
            long qty = Math.min(limit, e.capacity(1, 2, instrument, side));
            if (qty > 0) {
                e.commit(1, 2, instrument, side, qty);
            }
        }
        assertTrue(Math.abs(e.nopE8(1)) <= limit * RATE_SCALE);
    }

    @Test
    void failedCommitChangesNeitherLine() {
        bilateral(1, 1, 2, 2, CreditType.DSL, 1_000);
        assertNull(c.setValueDates(SPOT, TODAY, -1));
        c.commit(1, 2, SPOT, Side.BUY, 900);
        assertThrows(IllegalStateException.class, () -> c.commit(1, 2, SPOT, Side.BUY, 200));
        assertEquals(900 * RATE_SCALE, c.dslUsageE8(1, TODAY));
        assertEquals(900 * RATE_SCALE, c.dslUsageE8(2, TODAY));
    }

    @Test
    void swapFarLegBecomesNopExposureAfterNearSettlesAndIsReported() {
        bilateral(1, 1, 2, 2, CreditType.NOP, 1_000);
        assertNull(c.setValueDates(SWAP, TODAY, FAR));
        c.commit(1, 2, SWAP, Side.BUY, 5_000);
        assertEquals(0, c.nopE8(1), "legs net to zero at entry");
        List<Integer> breached = new ArrayList<>();
        assertNull(c.rollValueDate(TODAY + 1, (line, usage, limit) -> breached.add(line)));
        assertEquals(5_000 * RATE_SCALE, c.nopE8(1), "far leg remains");
        assertEquals(List.of(1, 2), breached);
        assertNull(c.setValueDates(SPOT, TODAY + 1, -1));
        assertEquals(0, c.capacity(1, 2, SPOT, Side.BUY), "exposure-increasing trades are blocked");
        assertEquals(6_000, c.capacity(1, 2, SPOT, Side.SELL), "reducing trades are allowed");
    }

    @Test
    void refusedInputsChangeNothing() {
        long version = c.version();
        assertEquals(CreditRefusal.UNKNOWN_INSTRUMENT, c.setValueDates(999, TODAY, -1));
        assertEquals(CreditRefusal.UNKNOWN_INSTRUMENT, c.setValueDates(-5, TODAY, -1));
        assertEquals(CreditRefusal.INVALID_VALUE_DATES, c.setValueDates(SPOT, -1, -1));
        assertEquals(CreditRefusal.INVALID_VALUE_DATES, c.setValueDates(SWAP, FAR, TODAY));
        assertEquals(CreditRefusal.INVALID_LINE_ID, c.setLine(Integer.MAX_VALUE, 1, CreditType.DSL, 1));
        assertEquals(CreditRefusal.NULL_FIELD, c.setLine(1, 1, null, 1));
        assertNull(c.setLine(1, 1, CreditType.DSL, 1_000));
        version = c.version();
        assertEquals(CreditRefusal.UNKNOWN_INSTRUMENT, c.setRelationship(1, 2, 999, 1));
        assertEquals(version, c.version(), "a refused relationship does not bump the version");
    }

    @Test
    void lineTypeCannotChangeWhileUsageIsOpen() {
        bilateral(1, 1, 2, 2, CreditType.DSL, 1_000);
        assertNull(c.setValueDates(SPOT, TODAY, -1));
        c.commit(1, 2, SPOT, Side.BUY, 100);
        assertEquals(CreditRefusal.LINE_TYPE_CHANGE_WITH_OPEN_USAGE, c.setLine(1, 1, CreditType.NOP, 1_000));
    }
}
