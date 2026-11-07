package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.MatchPriority;
import clob.refdata.PegReference;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pegged orders. The test instrument's peg throttle is 20ms, so ticks fall at 20ms, 40ms, ... */
class PeggedOrderTest {
    static final long MS = 1_000_000L;
    static final long TICK = 20 * MS;

    /** Visible 92 / 94. */
    Scenario s = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "4|SELL|1000|94|1000");

    @Test
    void newPegRejectedWithoutVisibleBidAndOffer() {
        Scenario t = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "4|SELL|0|94|1000");
        t.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        assertEquals(List.of(new Event.Rejected(0, 10, RejectReason.PEG_REFERENCE_UNAVAILABLE)), t.events());
    }

    @Test
    void nearTouchJoinsBestBidBehindExistingOrders() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        assertEquals(List.of(1L, 10L), s.book().orderIdsAt(Side.BUY, 92));
    }

    @Test
    void positiveDiscretionIsMoreAggressive() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 1).qty(1000).send();
        s.sell(11).peg(PegReference.NEAR_TOUCH, -1).qty(1000).send();
        assertEquals(List.of(10L), s.book().orderIdsAt(Side.BUY, 93));
        assertEquals(List.of(11L), s.book().orderIdsAt(Side.SELL, 95));
    }

    @Test
    void midPegRoundsDefensivelyWhenMidIsBetweenTicks() {
        Scenario t = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "4|SELL|1000|93|1000");
        t.buy(10).peg(PegReference.MID, 0).qty(1000).send();
        t.sell(11).peg(PegReference.MID, 0).qty(1000).send();
        assertEquals(List.of(1L, 10L), t.book().orderIdsAt(Side.BUY, 92));
        assertEquals(List.of(4L, 11L), t.book().orderIdsAt(Side.SELL, 93));
        assertEquals(List.of(), t.fills());
    }

    @Test
    void pegsAreExcludedFromTheReference() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 1).qty(1000).send(); // rests at 93
        assertEquals(92, s.book().referenceBid());
        s.buy(11).peg(PegReference.NEAR_TOUCH, 1).qty(1000).send();
        assertEquals(List.of(10L, 11L), s.book().orderIdsAt(Side.BUY, 93), "not 94: pegs don't chase pegs");
    }

    @Test
    void repricesOnlyOnTheThrottleGridAndLosesPriority() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.advance(MS).buy(5).limit(93).qty(1000).send();
        assertEquals(List.of(1L, 10L), s.book().orderIdsAt(Side.BUY, 92), "no reprice between ticks");
        s.clear().advance(TICK - MS);
        assertEquals(List.of(new Event.Repriced(TICK, 10, 93)), s.events());
        assertEquals(List.of(5L, 10L), s.book().orderIdsAt(Side.BUY, 93));
    }

    @Test
    void unchangedPriceKeepsPriority() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.buy(11).limit(92).qty(1000).send();
        s.clear().advance(TICK);
        assertEquals(List.of(), s.events());
        assertEquals(List.of(1L, 10L, 11L), s.book().orderIdsAt(Side.BUY, 92));
    }

    @Test
    void suspendedWhenASideDisappearsAndResumesWhenItReturns() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.cancel(4).clear().advance(TICK);
        assertEquals(List.of(new Event.Suspended(TICK, 10)), s.events());
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
        assertEquals(1000, s.engine().leavesOf(10), "suspended, not cancelled");
        s.sell(6).limit(95).qty(1000).send();
        s.clear().advance(TICK);
        assertEquals(List.of(new Event.Repriced(2 * TICK, 10, 92)), s.events());
        assertEquals(List.of(1L, 10L), s.book().orderIdsAt(Side.BUY, 92));
    }

    @Test
    void hiddenOrdersDoNotCountAsAVisibleReference() {
        s.buy(10).peg(PegReference.MID, 0).qty(1000).send();
        s.cancel(4).sell(6).limit(94).qty(1000).show(0).send();
        s.clear().advance(TICK);
        assertEquals(List.of(new Event.Suspended(TICK, 10)), s.events());
    }

    @Test
    void aggressivePegTradesOnEntry() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 2).qty(1000).send();
        assertEquals(List.of(fill(4, 94, 1000)), s.fills());
    }

    @Test
    void repriceThatCrossesTradesAtTheTick() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 1).qty(1000).send(); // 93
        s.advance(MS).buy(5).limit(93).qty(1000).send();            // reference bid becomes 93
        s.clear().advance(TICK - MS);
        assertEquals(List.of(new Event.Repriced(TICK, 10, 94)), s.events().subList(0, 1));
        assertEquals(List.of(fill(4, 94, 1000)), s.fills());
    }

    @Test
    void discretionAmendTakesEffectAtTheNextTick() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.advance(MS).replace(10).discretion(1).send();
        assertEquals(List.of(1L, 10L), s.book().orderIdsAt(Side.BUY, 92));
        s.advance(TICK);
        assertEquals(List.of(10L), s.book().orderIdsAt(Side.BUY, 93));
    }

    @Test
    void suspendedPegsCanBeCancelledAndExpireAtEndOfDay() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).day().send();
        s.buy(11).peg(PegReference.NEAR_TOUCH, 0).qty(1000).day().send();
        s.cancel(4).advance(TICK);
        s.clear().cancel(10).endOfDay();
        assertEquals(List.of(new Event.Cancelled(TICK, 10, CancelReason.PARTICIPANT, 1000),
                new Event.Cancelled(TICK, 11, CancelReason.END_OF_DAY, 1000)), s.events(), "order 1 is GTC");
        assertEquals(0, s.book().pegCount());
    }

    @Test
    void roundsToThePriceIncrement() {
        Scenario t = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).priceIncrement(5).mdPriceIncrement(5)
                .build()).book("1|BUY|1000|90|1000", "4|SELL|1000|100|1000");
        t.buy(10).peg(PegReference.MID, 1).qty(1000).send();       // 96 -> 95
        t.sell(11).peg(PegReference.NEAR_TOUCH, 1).qty(1000).send(); // 99 -> 100
        assertEquals(List.of(10L), t.book().orderIdsAt(Side.BUY, 95));
        assertEquals(List.of(4L, 11L), t.book().orderIdsAt(Side.SELL, 100));
    }

    @Test
    void firstTickIsOnTheGrid() {
        s.advance(25 * MS).buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.buy(5).limit(93).qty(1000).send();
        s.clear().advance(14 * MS);
        assertTrue(s.events().isEmpty(), "39ms: no tick yet");
        s.advance(MS);
        assertEquals(List.of(new Event.Repriced(40 * MS, 10, 93)), s.events());
        assertFalse(s.book().orderIdsAt(Side.BUY, 93).isEmpty());
    }
}
