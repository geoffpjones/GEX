package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcebergAndMinQtyTest {

    /** Min qty 1000, increment 500: allows sizes such as 1500 and residuals such as 500. */
    static Scenario halfIncrement(MatchPriority priority) {
        return Scenario.of(TestInstruments.example(priority).qtyIncrement(500).build());
    }

    @Test
    void icebergShowsOnlyItsSlice() {
        Scenario s = Scenario.of(TestInstruments.price());
        s.buy(1).iceberg(92, 1000).qty(3000).send();
        assertEquals(1000, s.book().visibleQtyAt(Side.BUY, 92));
        assertEquals(2000, s.book().hiddenQtyAt(Side.BUY, 92));
    }

    @Test
    void refreshedSliceLosesTimePriority() {
        Scenario s = Scenario.of(TestInstruments.price());
        s.buy(1).iceberg(92, 1000).qty(3000).send();
        s.buy(2).limit(92).qty(1000).send();
        s.sell(3).limit(92).qty(2000).ioc().send();
        assertEquals(List.of(fill(1, 92, 1000), fill(2, 92, 1000)), s.fills());
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
        assertEquals(2000, s.engine().leavesOf(1));
    }

    @Test
    void icebergRefreshesKeepVisibleTopUnexhausted() {
        // PLAN Q1c default: refreshed slices are displayed liquidity, so hidden 93 waits until the iceberg is gone.
        Scenario s = Scenario.of(TestInstruments.visibility()).book("3|BUY|0|93|1000");
        s.buy(1).iceberg(92, 1000).qty(3000).gtc().send();
        s.sell(10).limit(92).qty(4000).ioc().send();
        assertEquals(List.of(fill(1, 92, 1000), fill(1, 92, 1000), fill(1, 92, 1000), fill(3, 93, 1000)), s.fills());
    }

    @Test
    void sliceRemnantBelowMinQtyRefreshes() {
        Scenario s = halfIncrement(MatchPriority.PRICE);
        s.buy(1).iceberg(92, 2000).qty(5000).send();
        s.buy(2).limit(92).qty(1000).send();
        s.sell(3).limit(92).qty(1500).ioc().send();
        assertEquals(List.of(fill(1, 92, 1500)), s.fills());
        // 500 left in the slice is below min qty, so the iceberg refreshed behind order 2.
        assertEquals(List.of(2L, 1L), s.book().orderIdsAt(Side.BUY, 92));
        assertEquals(3000, s.book().visibleQtyAt(Side.BUY, 92)); // 1000 (order 2) + fresh 2000 slice
    }

    @Test
    void aggressorResidualBelowMinQtyIsCancelled() {
        Scenario s = halfIncrement(MatchPriority.VISIBILITY).book("1|BUY|1000|92|1000");
        s.sell(2).limit(92).qty(1500).day().send();
        assertEquals(List.of(fill(1, 92, 1000)), s.fills());
        assertEquals(new Event.Cancelled(0, 2, CancelReason.RESIDUAL_BELOW_MIN_QTY, 500),
                s.events().get(s.events().size() - 1));
        assertTrue(s.book().isEmpty());
    }

    @Test
    void passiveResidualBelowMinQtyIsCancelled() {
        Scenario s = halfIncrement(MatchPriority.VISIBILITY).book("1|BUY|1500|92|1500");
        s.sell(2).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(fill(1, 92, 1000)), s.fills());
        assertTrue(s.events().contains(new Event.Cancelled(0, 1, CancelReason.RESIDUAL_BELOW_MIN_QTY, 500)));
        assertTrue(s.book().isEmpty());
    }

    @Test
    void fokAccountsForResidualRule() {
        Scenario s = halfIncrement(MatchPriority.VISIBILITY).book("1|BUY|1000|92|1000");
        s.sell(2).limit(92).qty(1500).fok().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 2, CancelReason.FOK_UNFILLED, 1500), s.events().get(1));
    }
}
