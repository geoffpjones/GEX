package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.refdata.InstrumentType;
import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: matching under bilateral credit. Test instrument base currency has a USD rate of 1, so
 * USD limits equal base quantities. Order ids map to accounts 1 + (id - 1) % 64 unless set.
 */
class CreditMatchingTest {
    static final long E8 = CreditEngine.RATE_SCALE;

    @Test
    void bookMayBeCrossedBetweenAccountsWithoutCredit() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        s.buy(1).limit(95).qty(1000).send();
        s.sell(2).limit(94).qty(1000).send();
        assertEquals(List.of(), s.fills());
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 95));
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.SELL, 94));
    }

    @Test
    void oneWayCreditIsNotEnough() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        s.creditLine(100, 1, CreditType.UNLIMITED, 0).relationship(1, 2, 100);
        s.buy(1).limit(95).qty(1000).send();
        s.sell(2).limit(94).qty(1000).send();
        assertEquals(List.of(), s.fills());
        s.creditLine(200, 2, CreditType.UNLIMITED, 0).relationship(2, 1, 200);
        s.sell(66).limit(94).qty(1000).send(); // account 2 again
        assertEquals(List.of(fill(1, 95, 1000)), s.fills());
    }

    @Test
    void walkSkipsIneligibleOrdersForWorsePrices() {
        Scenario s = Scenario.of(TestInstruments.price()).book("2|BUY|1000|93|1000", "3|BUY|1000|92|1000");
        s.relationship(10, 2, CreditEngine.NO_LINE);
        s.sell(10).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(fill(3, 92, 1000)), s.fills());
    }

    @Test
    void selfTradeIsPreventedByAbsenceOfSelfCredit() {
        Scenario s = Scenario.of(TestInstruments.visibility());
        s.buy(1).limit(92).qty(1000).send();
        s.sell(65).limit(92).qty(1000).send(); // also account 1
        assertEquals(List.of(), s.fills());
    }

    @Test
    void perInstrumentOverrideToNoCredit() {
        Scenario s = Scenario.of(TestInstruments.visibility()).book("2|BUY|1000|92|1000");
        s.relationship(10, 2, s.instrument().id(), CreditEngine.NO_LINE);
        s.sell(10).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(), s.fills());
    }

    @Test
    void dslClipsFillToValidSizeAndWalkContinues() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        int[] lines = s.bilateral(10, 2, CreditType.DSL, 2500);
        s.bilateral(10, 3, CreditType.UNLIMITED, 0);
        s.book("2|BUY|5000|92|5000", "3|BUY|5000|92|5000");
        s.sell(10).limit(92).qty(5000).ioc().send();
        assertEquals(List.of(fill(2, 92, 2000), fill(3, 92, 3000)), s.fills());
        assertEquals(3000, s.engine().leavesOf(2));
        assertEquals(2000 * E8, s.credit().dslUsageE8(lines[0], Scenario.SPOT_DATE));
        assertEquals(2000 * E8, s.credit().dslUsageE8(lines[1], Scenario.SPOT_DATE));
    }

    @Test
    void creditBelowMinQtySkipsTheOrder() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        s.bilateral(10, 2, CreditType.DSL, 500);
        s.bilateral(10, 3, CreditType.UNLIMITED, 0);
        s.book("2|BUY|5000|92|5000", "3|BUY|5000|92|5000");
        s.sell(10).limit(92).qty(5000).ioc().send();
        assertEquals(List.of(fill(3, 92, 5000)), s.fills());
    }

    @Test
    void creditClippedAggressorResidualBelowMinIsCancelled() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).qtyIncrement(500).build());
        s.creditLine(500, 10, CreditType.DSL, 1500).relationship(10, 2, 500);
        s.book("2|BUY|2000|92|2000");
        s.sell(10).limit(92).qty(2000).day().send();
        assertEquals(List.of(fill(2, 92, 1500)), s.fills());
        assertTrue(s.events().contains(new Event.Cancelled(0, 10, CancelReason.RESIDUAL_BELOW_MIN_QTY, 500)));
        assertTrue(s.events().contains(new Event.Cancelled(0, 2, CancelReason.RESIDUAL_BELOW_MIN_QTY, 500)));
    }

    @Test
    void usdRateScalesCapacity() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility()).usdRate("TEST", 2 * E8);
        s.bilateral(10, 2, CreditType.DSL, 5000);
        s.book("2|BUY|5000|92|5000");
        s.sell(10).limit(92).qty(5000).ioc().send();
        assertEquals(List.of(fill(2, 92, 2000)), s.fills(), "USD 5000 at 2.0 = 2500 base, rounded down to 2000");
    }

    @Test
    void nopBlocksIncreasesButAllowsReductions() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        int[] lines = s.bilateral(10, 2, CreditType.NOP, 3000);
        s.book("2|SELL|10000|94|10000", "66|BUY|5000|93|5000"); // both account 2
        s.buy(100).account(10).user(10).limit(94).qty(5000).ioc().send();
        assertEquals(List.of(fill(2, 94, 3000)), s.fills());
        s.clear().buy(101).account(10).user(10).limit(94).qty(1000).ioc().send();
        assertEquals(List.of(), s.fills(), "account 10 is long 3000 against 2: at its limit");
        s.clear().sell(102).account(10).user(10).limit(93).qty(2000).ioc().send();
        assertEquals(List.of(fill(66, 93, 2000)), s.fills(), "reducing trade allowed");
        assertEquals(1000 * E8, s.credit().nopE8(lines[0]));
        s.clear().buy(103).account(10).user(10).limit(94).qty(5000).ioc().send();
        assertEquals(List.of(fill(2, 94, 2000)), s.fills());
    }

    @Test
    void valueDateChangeAndRollReleaseDsl() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        int[] lines = s.bilateral(10, 2, CreditType.DSL, 2000);
        s.book("2|BUY|10000|92|10000");
        s.sell(10).limit(92).qty(2000).ioc().send();
        s.clear().sell(74).limit(92).qty(1000).ioc().send(); // account 10 again
        assertEquals(List.of(), s.fills());
        s.valueDates(Scenario.SPOT_DATE + 1, -1);
        s.clear().sell(138).limit(92).qty(2000).ioc().send(); // account 10
        assertEquals(List.of(fill(2, 92, 2000)), s.fills());
        s.rollValueDate(Scenario.SPOT_DATE + 1);
        assertEquals(0, s.credit().dslUsageE8(lines[0], Scenario.SPOT_DATE));
        assertEquals(2000 * E8, s.credit().dslUsageE8(lines[0], Scenario.SPOT_DATE + 1));
    }

    @Test
    void swapConsumesDslOnNearAndFarDates() {
        Scenario s = Scenario.withoutCredit(TestInstruments.example(MatchPriority.VISIBILITY)
                .type(InstrumentType.SWAP).build());
        int[] lines = s.bilateral(10, 2, CreditType.DSL, 3000);
        s.book("2|BUY|10000|-5|10000"); // swap points may be negative
        s.sell(10).limit(-5).qty(5000).ioc().send();
        assertEquals(List.of(fill(2, -5, 3000)), s.fills());
        assertEquals(3000 * E8, s.credit().dslUsageE8(lines[0], Scenario.SPOT_DATE));
        assertEquals(3000 * E8, s.credit().dslUsageE8(lines[0], Scenario.FAR_DATE));
    }

    @Test
    void reducedLimitBlocksFurtherTrades() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        int[] lines = s.bilateral(10, 2, CreditType.DSL, 5000);
        s.book("2|BUY|10000|92|10000");
        s.sell(10).limit(92).qty(3000).ioc().send();
        s.creditLine(lines[0], 10, CreditType.DSL, 2000);
        s.clear().sell(74).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(), s.fills());
    }

    @Test
    void fokSucceedsAcrossCreditClippedCounterparties() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        int[] lines = s.bilateral(10, 2, CreditType.DSL, 2000);
        s.bilateral(10, 3, CreditType.UNLIMITED, 0);
        s.book("2|BUY|3000|92|3000", "3|BUY|1000|91|1000");
        s.sell(10).limit(91).qty(4000).fok().send();
        assertEquals(List.of(), s.fills(), "only 3000 fillable within credit");
        assertEquals(0, s.credit().dslUsageE8(lines[0], Scenario.SPOT_DATE), "dry run left no usage");
        s.sell(74).limit(91).qty(3000).fok().send(); // account 10
        assertEquals(List.of(fill(2, 92, 2000), fill(3, 91, 1000)), s.fills());
    }

    @Test
    void visibilityTopLevelIsScreenedForTheAggressor() {
        // Visible 92 belongs to an account the aggressor cannot trade with, so the aggressor's visible
        // top is 91. Exhausting it unlocks hidden 93 (PLAN Q1c default).
        Scenario s = Scenario.of(TestInstruments.visibility())
                .book("1|BUY|0|93|1000", "2|BUY|1000|92|1000", "3|BUY|1000|91|1000");
        s.relationship(10, 2, CreditEngine.NO_LINE);
        s.sell(10).limit(91).qty(3000).ioc().send();
        assertEquals(List.of(fill(3, 91, 1000), fill(1, 93, 1000)), s.fills());
    }
}
