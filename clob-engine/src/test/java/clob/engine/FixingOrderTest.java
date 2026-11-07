package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixingOrderTest {
    static final long MS = 1_000_000L;
    static final long CUTOFF = 100 * MS;
    static final long FIXING = 200 * MS;

    Scenario s = Scenario.of(TestInstruments.visibility());

    @Test
    void rejectedWhenNoOccurrenceIsOpen() {
        s.buy(10).fixing().qty(1000).send();
        assertEquals(List.of(new Event.Rejected(0, 10, RejectReason.FIXING_NOT_OPEN)), s.events());
    }

    @Test
    void fullLifecycleMatchCutoffRate() {
        assertTrue(s.fixingSchedule(CUTOFF, FIXING));
        s.buy(10).fixing().qty(3000).send();
        s.sell(11).fixing().qty(2000).send();
        assertEquals(List.of(new Event.FixingMatched(0, 1, 0, Scenario.FIXING_EVENT, 11, 10, Side.SELL, 2000, 0, 1000)),
                s.events(Event.FixingMatched.class));
        assertEquals(List.of(), s.events(Event.Trade.class), "no price until the rate arrives");
        assertFalse(s.fixingRate(108_123), "no rate before cut-off");

        s.clear().advance(CUTOFF);
        assertEquals(List.of(new Event.Cancelled(CUTOFF, 10, CancelReason.FIXING_CUTOFF, 1000)), s.events());
        s.buy(12).fixing().qty(1000).send();
        assertEquals(new Event.Rejected(CUTOFF, 12, RejectReason.FIXING_NOT_OPEN), s.events().get(1));

        s.clear().advance(FIXING - CUTOFF);
        assertTrue(s.fixingRate(108_123));
        assertEquals(List.of(new Event.Trade(FIXING, 1, 0, 11, 10, Side.SELL, 108_123, 0, 2000, -1, -1)), s.events());
        assertFalse(s.fixingRate(108_123), "already priced");
    }

    @Test
    void fixingOrdersOnlyMatchEachOther() {
        s.fixingSchedule(CUTOFF, FIXING);
        s.buy(10).fixing().qty(1000).send();
        s.sell(11).limit(1).qty(1000).send();
        assertEquals(List.of(), s.events(Event.FixingMatched.class));
        assertEquals(List.of(), s.fills());
    }

    @Test
    void timePriorityAndCredit() {
        s.fixingSchedule(CUTOFF, FIXING);
        s.relationship(12, 10, CreditEngine.NO_LINE);
        s.buy(10).fixing().qty(1000).send();
        s.buy(11).fixing().qty(1000).send();
        s.sell(12).fixing().qty(1000).send();
        assertEquals(11, s.events(Event.FixingMatched.class).get(0).passiveOrderId(), "10 skipped: no credit");
    }

    @Test
    void cannotRescheduleWhileAwaitingRate() {
        s.fixingSchedule(CUTOFF, FIXING);
        s.buy(10).fixing().qty(1000).send();
        s.sell(11).fixing().qty(1000).send();
        s.advance(CUTOFF);
        assertFalse(s.fixingSchedule(CUTOFF + 1000 * MS, FIXING + 1000 * MS));
        s.advance(FIXING - CUTOFF);
        s.fixingRate(100);
        assertTrue(s.fixingSchedule(FIXING + 1000 * MS, FIXING + 2000 * MS));
    }

    @Test
    void mustRestAndCannotBeAmended() {
        s.fixingSchedule(CUTOFF, FIXING);
        s.buy(10).fixing().qty(1000).ioc().send();
        assertEquals(new Event.Rejected(0, 10, RejectReason.INVALID_TIF_FOR_TYPE), s.events().get(0));
        s.buy(11).fixing().qty(1000).send();
        s.clear().replace(11).qty(2000).send();
        assertEquals(List.of(new Event.RequestRejected(0, 11, RequestType.REPLACE, RequestRejectReason.NOT_REPLACEABLE)),
                s.events());
    }

    @Test
    void cancelBeforeCutoff() {
        s.fixingSchedule(CUTOFF, FIXING);
        s.buy(10).fixing().qty(1000).send();
        s.clear().cancel(10);
        assertEquals(List.of(new Event.Cancelled(0, 10, CancelReason.PARTICIPANT, 1000)), s.events());
        s.clear().advance(CUTOFF);
        assertEquals(List.of(), s.events());
    }
}
