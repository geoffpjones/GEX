package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.MatchPriority;
import clob.refdata.PegReference;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTest {
    static final long MS = 1_000_000L;

    Scenario s = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "2|SELL|1000|94|1000");

    @Test
    void newOrdersAndAmendsRejectedOutsideOpenCancelsAllowed() {
        s.session(SessionState.HALTED);
        s.clear().buy(10).limit(93).qty(1000).send();
        s.replace(1).price(91).send();
        s.cancel(2);
        assertEquals(List.of(new Event.Rejected(0, 10, RejectReason.MARKET_HALTED),
                new Event.RequestRejected(0, 1, RequestType.REPLACE, RequestRejectReason.MARKET_NOT_OPEN),
                new Event.Cancelled(0, 2, CancelReason.PARTICIPANT, 1000)), s.events());
        s.session(SessionState.CLOSED).clear().buy(11).limit(93).qty(1000).send();
        assertEquals(List.of(new Event.Rejected(0, 11, RejectReason.MARKET_NOT_OPEN)), s.events());
    }

    @Test
    void sessionChangesAreReported() {
        s.clear().session(SessionState.HALTED).session(SessionState.OPEN);
        assertEquals(List.of(new Event.Session(0, 0, SessionState.HALTED), new Event.Session(0, 0, SessionState.OPEN)),
                s.events());
    }

    @Test
    void aHaltCancelsOrdersWaitingInARace() {
        Scenario t = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).takerRaceNanos(5 * MS).build())
                .book("1|BUY|1000|92|1000");
        t.sell(10).limit(92).qty(1000).ioc().send();
        t.clear().session(SessionState.HALTED);
        assertEquals(List.of(new Event.Session(0, 0, SessionState.HALTED),
                new Event.Cancelled(0, 10, CancelReason.HALTED, 1000)), t.events());
        t.clear().advance(10 * MS).session(SessionState.OPEN);
        assertEquals(List.of(), t.fills(), "nothing is left to run when it reopens");
    }

    @Test
    void pegsDoNotRepriceWhileHalted() {
        s.buy(10).peg(PegReference.NEAR_TOUCH, 0).qty(1000).send();
        s.buy(5).limit(93).qty(1000).send();
        s.session(SessionState.HALTED).clear().advance(20 * MS);
        assertEquals(List.of(), s.events(Event.Repriced.class));
        s.session(SessionState.OPEN).advance(20 * MS);
        assertEquals(List.of(10L), s.book().orderIdsAt(Side.BUY, 93).subList(1, 2));
    }

    @Test
    void midOrdersMatchWhenTheInstrumentReopens() {
        s.buy(10).mid(-1).qty(1000).send();
        s.sell(11).mid(0).qty(1000).send();
        s.session(SessionState.HALTED);
        s.replace(10).discretion(0).send(); // rejected: halted
        s.session(SessionState.OPEN);
        assertEquals(List.of(), s.fills());
        assertTrue(s.events(Event.RequestRejected.class).size() == 1);
    }

    @Test
    void gttStillExpiresWhileHalted() {
        s.buy(10).limit(91).qty(1000).gtt(5 * MS).send();
        s.session(SessionState.HALTED).clear().advance(10 * MS);
        assertEquals(List.of(new Event.Cancelled(5 * MS, 10, CancelReason.EXPIRED, 1000)), s.events());
    }
}
