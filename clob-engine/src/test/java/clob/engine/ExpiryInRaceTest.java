package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R05 / D1: an order waiting in a race expires on time and leaves its ticket. */
class ExpiryInRaceTest {
    static final long MS = 1_000_000L;

    Scenario taker() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).takerRaceNanos(5 * MS).build());
        s.buy(1).limit(100).qty(1000).gtc().send();
        return s;
    }

    Scenario maker() {
        return Scenario.of(TestInstruments.example(MatchPriority.PRICE).makerRaceNanos(5 * MS).build());
    }

    @Test
    void gttExpiresBeforeTakerRaceCloses() {
        Scenario s = taker();
        s.sell(2).limit(100).qty(1000).gtt(2 * MS).send();
        s.clear().advance(3 * MS);
        assertEquals(List.of(new Event.Cancelled(2 * MS, 2, CancelReason.EXPIRED, 1000)), s.events());
        s.clear().advance(2 * MS);
        assertEquals(List.of(), s.fills(), "an expired order never trades");
        assertEquals(-1, s.engine().leavesOf(2));
    }

    @Test
    void gttExpiringAtExactlyTheRaceCloseDoesNotTrade() {
        Scenario s = taker();
        s.sell(2).limit(100).qty(1000).gtt(5 * MS).send();
        s.clear().advance(5 * MS);
        assertEquals(List.of(), s.fills());
        assertTrue(s.events().contains(new Event.Cancelled(5 * MS, 2, CancelReason.EXPIRED, 1000)));
    }

    @Test
    void gttExpiringAfterTheRaceClosesTrades() {
        Scenario s = taker();
        s.sell(2).limit(100).qty(1000).gtt(6 * MS).send();
        s.clear().advance(5 * MS);
        assertEquals(1, s.fills().size());
    }

    @Test
    void expiryInMakerRace() {
        Scenario s = maker();
        s.buy(1).limit(100).qty(1000).gtt(2 * MS).send();
        s.buy(2).limit(100).qty(1000).gtc().send();
        s.clear().advance(5 * MS);
        assertTrue(s.events().contains(new Event.Cancelled(2 * MS, 1, CancelReason.EXPIRED, 1000)));
        assertEquals(List.of(2L), s.book().orderIdsAt(clob.common.Side.BUY, 100));
    }

    @Test
    void dayOrderInARaceCancelsAtEndOfDay() {
        Scenario s = taker();
        s.sell(2).limit(100).qty(1000).day().send();
        s.clear().advance(MS).endOfDay();
        assertEquals(List.of(new Event.Cancelled(MS, 2, CancelReason.END_OF_DAY, 1000)), s.events());
        s.clear().advance(5 * MS);
        assertEquals(List.of(), s.fills());
    }

    @Test
    void otherOrdersOnTheTicketStillRun() {
        Scenario s = taker();
        s.sell(2).account(7).user(7).limit(100).qty(1000).gtt(2 * MS).send();
        s.sell(3).account(7).user(7).limit(100).qty(1000).gtc().send();
        s.advance(5 * MS);
        assertEquals(1, s.fills().size());
        assertEquals(3L, s.events(Event.Trade.class).get(0).aggressorOrderId());
        assertFalse(s.book().takerRaceRunning(clob.common.Side.SELL));
    }

    @Test
    void earlyFixingRateIsRefused() {
        long cutoff = 100 * MS;
        long fixing = 200 * MS;
        Scenario s = Scenario.of(TestInstruments.visibility());
        assertTrue(s.fixingSchedule(cutoff, fixing));
        s.buy(10).fixing().qty(1000).send();
        s.sell(11).fixing().qty(1000).send();
        s.clear().advance(cutoff);
        assertFalse(s.fixingRate(5), "R14: between cut-off and fixing time");
        assertEquals(List.of(), s.events(Event.Trade.class));
        s.advance(fixing - cutoff - 1);
        assertFalse(s.fixingRate(5), "one nanosecond early");
        s.advance(1);
        assertTrue(s.fixingRate(5), "exactly at the fixing time");
        assertEquals(1, s.events(Event.Trade.class).size());
    }
}
