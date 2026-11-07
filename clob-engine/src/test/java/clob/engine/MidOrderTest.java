package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mid orders, including the four worked examples from requirements.md. */
class MidOrderTest {
    static final long MS = 1_000_000L;

    /** "assume market is 92/94 visible 1000 x 1000" */
    Scenario s = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "2|SELL|1000|94|1000");

    private void midPair(long buyDiscretion, long sellDiscretion) {
        s.buy(10).mid(buyDiscretion).qty(1000).send();
        s.sell(11).mid(sellDiscretion).qty(1000).send();
    }

    @Test
    void requirementsExample1BothZeroMatchAt93() {
        midPair(0, 0);
        assertEquals(List.of(fill(10, 93, 1000)), s.fills());
    }

    @Test
    void requirementsExample2SellMinusOneDoesNotMatch() {
        midPair(0, -1);
        assertEquals(List.of(), s.fills());
        assertEquals(List.of(10L), s.book().midOrderIds(Side.BUY));
        assertEquals(List.of(11L), s.book().midOrderIds(Side.SELL));
    }

    @Test
    void requirementsExample3PlusOneMinusOneMatchAt94() {
        midPair(1, -1);
        assertEquals(List.of(fill(10, 94, 1000)), s.fills());
    }

    @Test
    void requirementsExample4BothPlusOneMatchAt93() {
        midPair(1, 1);
        assertEquals(List.of(fill(10, 93, 1000)), s.fills());
    }

    @Test
    void midOrdersAreNotInTheMainBook() {
        s.buy(10).mid(0).qty(1000).send();
        s.sell(11).limit(93).qty(1000).send();
        assertEquals(List.of(), s.fills());
        assertEquals(List.of(11L), s.book().orderIdsAt(Side.SELL, 93));
    }

    @Test
    void halfTickMidMatchesOneDecimalBeyondPrecision() {
        // Confirmed 2026-11-07: mid may trade at one decimal place beyond the instrument's precision.
        Scenario t = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "2|SELL|1000|93|1000");
        t.buy(10).mid(0).qty(1000).send();
        t.sell(11).mid(0).qty(1000).send();
        assertEquals(List.of(fill(10, "92.5", 1000)), t.fills());
        assertEquals(1, t.events(Event.Trade.class).get(0).priceDecimals());
        assertEquals(925, t.events(Event.Trade.class).get(0).price());
    }

    @Test
    void halfTickMidWithDiscretionClampsToTheLimit() {
        Scenario t = Scenario.of(TestInstruments.visibility()).book("1|BUY|1000|92|1000", "2|SELL|1000|93|1000");
        t.buy(10).mid(-1).qty(1000).send();  // limit 91.5
        t.sell(11).mid(1).qty(1000).send();  // limit 91.5
        assertEquals(List.of(fill(10, "91.5", 1000)), t.fills());
    }

    @Test
    void midIgnoresTheOrderPriceIncrement() {
        Scenario t = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).priceIncrement(5).mdPriceIncrement(5)
                .build()).book("1|BUY|1000|90|1000", "2|SELL|1000|95|1000");
        t.buy(10).mid(0).qty(1000).send();
        t.sell(11).mid(0).qty(1000).send();
        assertEquals(List.of(fill(10, "92.5", 1000)), t.fills());
    }

    @Test
    void restWithoutVisibleMarketThenMatchWhenItAppears() {
        Scenario t = Scenario.of(TestInstruments.visibility());
        t.buy(10).mid(0).qty(1000).send();
        t.sell(11).mid(0).qty(1000).send();
        assertEquals(List.of(), t.fills());
        t.advance(MS).buy(1).limit(92).qty(1000).send();
        t.sell(2).limit(94).qty(1000).send();
        assertEquals(List.of(new Event.Trade(MS, 1, 0, 11, 10, Side.SELL, 930, 1, 1000, 0, 0)),
                t.events(Event.Trade.class));
    }

    @Test
    void restingMidOrdersTradeAtTheMidWhenTheMarketReturns() {
        s.cancel(2); // no visible offer: mid orders cannot match
        s.buy(10).mid(0).qty(1000).send();
        s.sell(11).mid(0).qty(1000).send();
        assertEquals(List.of(), s.fills());
        s.sell(3).limit(95).qty(1000).send();
        assertEquals(List.of(fill(10, "93.5", 1000)), s.fills());
    }

    @Test
    void timePriorityAmongCrossingMidOrders() {
        s.buy(10).mid(0).qty(1000).send();
        s.buy(12).mid(5).qty(1000).send();
        s.sell(11).mid(0).qty(1000).send();
        assertEquals(List.of(fill(10, 93, 1000)), s.fills());
        assertEquals(List.of(12L), s.book().midOrderIds(Side.BUY));
    }

    @Test
    void creditApplies() {
        s.relationship(11, 10, CreditEngine.NO_LINE);
        midPair(0, 0);
        assertEquals(List.of(), s.fills());
    }

    @Test
    void fokMidKilledWhenNotFullyFillable() {
        s.buy(10).mid(0).qty(1000).send();
        s.sell(11).mid(0).qty(2000).fok().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 11, CancelReason.FOK_UNFILLED, 2000), s.events().get(s.events().size() - 1));
        s.sell(13).mid(0).qty(1000).fok().send();
        assertEquals(List.of(fill(10, 93, 1000)), s.fills());
    }

    @Test
    void discretionAmendCanTrigger() {
        midPair(0, -1);
        s.replace(11).discretion(0).send();
        assertEquals(List.of(fill(10, 93, 1000)), s.fills());
    }

    @Test
    void midTtlIsTheMinimumRestingTime() {
        Scenario t = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).midTtlNanos(5 * MS)
                .rejectOnTtl(true).build());
        t.buy(10).mid(0).qty(1000).send();
        t.clear().advance(MS).cancel(10);
        assertEquals(List.of(new Event.RequestRejected(MS, 10, RequestType.CANCEL, RequestRejectReason.INSIDE_MIN_TTL)),
                t.events());
    }

    @Test
    void tradePriceIsMidClampedToLimits() {
        assertEquals(925, MidMatcher.tradePrice10(925, 935, 925));
        assertEquals(940, MidMatcher.tradePrice10(930, 940, 940));
        assertEquals(930, MidMatcher.tradePrice10(930, 940, 920));
    }
}
