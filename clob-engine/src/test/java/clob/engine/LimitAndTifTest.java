package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

class LimitAndTifTest {
    Scenario s = Scenario.of(TestInstruments.visibility());

    @Test
    void restsWhenNothingCrosses() {
        s.buy(1).limit(92).qty(1000).send();
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
        assertEquals(1000, s.book().visibleQtyAt(Side.BUY, 92));
    }

    @Test
    void aggressorTradesAtRestingPrice() {
        s.sell(1).limit(94).qty(1000).send();
        s.buy(2).limit(95).qty(1000).send();
        assertEquals(List.of(fill(1, 94, 1000)), s.fills());
        assertTrue(s.book().isEmpty());
    }

    @Test
    void timePriorityWithinLevel() {
        s.book("1|SELL|1000|94|1000", "2|SELL|1000|94|1000");
        s.buy(3).limit(94).qty(1000).send();
        assertEquals(List.of(fill(1, 94, 1000)), s.fills());
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.SELL, 94));
    }

    @Test
    void partiallyFilledAggressorRestsRemainder() {
        s.book("1|SELL|1000|94|1000");
        s.buy(2).limit(95).qty(3000).send();
        assertEquals(List.of(fill(1, 94, 1000)), s.fills());
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.BUY, 95));
        assertEquals(2000, s.engine().leavesOf(2));
    }

    @Test
    void iocCancelsRemainder() {
        s.book("1|BUY|1000|92|1000");
        s.sell(2).limit(92).qty(2000).ioc().send();
        assertEquals(List.of(fill(1, 92, 1000)), s.fills());
        assertEquals(new Event.Cancelled(0, 2, CancelReason.IOC_UNFILLED, 1000), s.events().get(s.events().size() - 1));
        assertTrue(s.book().isEmpty());
    }

    @Test
    void fokKillsWithoutTouchingBookWhenNotFullyFillable() {
        s.book("1|BUY|1000|92|1000");
        s.sell(2).limit(92).qty(2000).fok().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 2, CancelReason.FOK_UNFILLED, 2000), s.events().get(1));
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
    }

    @Test
    void fokFillsAcrossLevels() {
        s.book("1|BUY|1000|92|1000", "2|BUY|1000|91|1000");
        s.sell(3).limit(91).qty(2000).fok().send();
        assertEquals(List.of(fill(1, 92, 1000), fill(2, 91, 1000)), s.fills());
    }

    @Test
    void fokRespectsVisibilityRule() {
        // Hidden 93 only unlocks after visible 92 is exhausted, so 2000 is fillable but 3000 is not.
        s.book("1|BUY|0|93|1000", "2|BUY|1000|92|1000");
        s.sell(3).limit(92).qty(3000).fok().send();
        assertEquals(List.of(), s.fills());
        s.sell(4).limit(92).qty(2000).fok().send();
        assertEquals(List.of(fill(2, 92, 1000), fill(1, 93, 1000)), s.fills());
    }

    @Test
    void marketOrderSweepsAndCancelsRemainder() {
        s.book("1|BUY|1000|92|1000", "2|BUY|1000|91|1000");
        s.sell(3).market().qty(3000).day().send();
        assertEquals(List.of(fill(1, 92, 1000), fill(2, 91, 1000)), s.fills());
        assertEquals(new Event.Cancelled(0, 3, CancelReason.MARKET_UNFILLED, 1000), s.events().get(s.events().size() - 1));
    }

    @Test
    void marketOrderWithEmptyBookIsCancelled() {
        s.buy(1).market().qty(1000).ioc().send();
        assertEquals(List.of(new Event.Accepted(0, 1), new Event.Cancelled(0, 1, CancelReason.MARKET_UNFILLED, 1000)),
                s.events());
    }

    @Test
    void endOfDayCancelsDayOrdersAndKeepsGtc() {
        s.buy(1).limit(92).qty(1000).day().send();
        s.buy(2).limit(92).qty(1000).gtc().send();
        s.clear().advance(100).endOfDay();
        assertEquals(List.of(new Event.Cancelled(100, 1, CancelReason.END_OF_DAY, 1000)), s.events());
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.BUY, 92));
    }

    @Test
    void gttExpiresAtItsDeadline() {
        s.buy(1).limit(92).qty(1000).gtt(100).send();
        s.clear().advance(99);
        assertEquals(List.of(), s.events());
        s.advance(5);
        assertEquals(List.of(new Event.Cancelled(100, 1, CancelReason.EXPIRED, 1000)), s.events());
        assertTrue(s.book().isEmpty());
    }

    @Test
    void gttWithPastExpiryIsRejected() {
        s.advance(100);
        s.buy(1).limit(92).qty(1000).gtt(100).send();
        assertEquals(List.of(new Event.Rejected(100, 1, RejectReason.INVALID_EXPIRE_TIME)), s.events());
    }

    @Test
    void timerDueAtInputTimeFiresFirst() {
        s.buy(1).limit(92).qty(1000).gtt(100).send();
        s.advance(100);
        s.sell(2).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(), s.fills());
    }
}
