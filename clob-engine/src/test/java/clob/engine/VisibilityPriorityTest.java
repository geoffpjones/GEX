package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;

import clob.common.Side;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The worked example from requirements.md "Order matching", under both priority modes. */
class VisibilityPriorityTest {
    /** id|side|show|price|qty, verbatim from the requirements. */
    static final String[] MARKET = {
        "1|BUY|0|93|1000",
        "2|BUY|1000|92|1000",
        "3|BUY|1000|91|1000",
        "4|SELL|1000|94|1000",
    };

    @Nested
    class Visibility {
        Scenario s = Scenario.of(TestInstruments.visibility()).book(MARKET);

        @Test
        void sell1000At93DoesNotMatch() {
            s.sell(10).limit(93).qty(1000).ioc().send();
            assertEquals(List.of(), s.fills());
            assertEquals(List.of(new Event.Accepted(0, 10), new Event.Cancelled(0, 10, CancelReason.IOC_UNFILLED, 1000)),
                    s.events());
        }

        @Test
        void sell1000At92MatchesAt92() {
            s.sell(10).limit(92).qty(1000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000)), s.fills());
        }

        @Test
        void sell2000At92MatchesId2ThenId1() {
            s.sell(10).limit(92).qty(2000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000), fill(1, 93, 1000)), s.fills());
        }

        @Test
        void sell3000At91MatchesId2ThenId1ThenId3() {
            s.sell(10).limit(91).qty(3000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000), fill(1, 93, 1000), fill(3, 91, 1000)), s.fills());
        }

        @Test
        void partialFillOfVisibleTopDoesNotUnlockHidden() {
            Scenario t = Scenario.of(TestInstruments.visibility()).book("1|BUY|0|93|1000", "2|BUY|2000|92|2000");
            t.sell(10).limit(92).qty(1000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000)), t.fills());
            t.clear().sell(11).limit(92).qty(2000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000), fill(1, 93, 1000)), t.fills());
        }

        @Test
        void everyDisplayedOrderAtTopMustBeExhaustedBeforeHiddenUnlocks() {
            Scenario t = Scenario.of(TestInstruments.visibility())
                    .book("1|BUY|0|93|1000", "2|BUY|1000|92|1000", "5|BUY|1000|92|1000");
            t.sell(10).limit(92).qty(3000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000), fill(5, 92, 1000), fill(1, 93, 1000)), t.fills());
        }

        @Test
        void sellThatRestsAtTheHiddenPriceTradesWithTheHiddenBid() {
            s.sell(10).limit(93).qty(1000).day().send();
            assertEquals(List.of(fill(1, 93, 1000)), s.fills());
            assertEquals(List.of(), s.book().orderIdsAt(Side.SELL, 93));
            assertEquals(List.of(), s.book().orderIdsAt(Side.BUY, 93));
        }
    }

    @Nested
    class Price {
        Scenario s = Scenario.of(TestInstruments.price()).book(MARKET);

        @Test
        void sell1000At93MatchesHiddenAt93() {
            s.sell(10).limit(93).qty(1000).ioc().send();
            assertEquals(List.of(fill(1, 93, 1000)), s.fills());
        }

        @Test
        void sell1000At92MatchesAt93() {
            s.sell(10).limit(92).qty(1000).ioc().send();
            assertEquals(List.of(fill(1, 93, 1000)), s.fills());
        }

        @Test
        void sell2000At92MatchesBestPriceFirst() {
            s.sell(10).limit(92).qty(2000).ioc().send();
            assertEquals(List.of(fill(1, 93, 1000), fill(2, 92, 1000)), s.fills());
        }

        @Test
        void sell3000At91SweepsInPriceOrder() {
            s.sell(10).limit(91).qty(3000).ioc().send();
            assertEquals(List.of(fill(1, 93, 1000), fill(2, 92, 1000), fill(3, 91, 1000)), s.fills());
        }

        @Test
        void visibleBeatsHiddenAtSamePriceEvenIfHiddenIsOlder() {
            Scenario t = Scenario.of(TestInstruments.price()).book("1|BUY|0|92|1000", "2|BUY|1000|92|1000");
            t.sell(10).limit(92).qty(2000).ioc().send();
            assertEquals(List.of(fill(2, 92, 1000), fill(1, 92, 1000)), t.fills());
        }
    }
}
