package clob.marketdata;

import static clob.marketdata.MdHarness.MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.engine.MatchingEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataServiceTest {

    @Test
    void requirementsBucketingExample() {
        // BUY 1.0000, 0.9999, 0.9998, 0.9994 x 1000 -> 1.0000 x 1000; 0.9995 x 2000; 0.9990 x 1000
        MdHarness h = new MdHarness(MdHarness.fourDp().build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.order(1, 1, Side.BUY, 10000, 1000).order(2, 1, Side.BUY, 9999, 1000)
                .order(3, 1, Side.BUY, 9998, 1000).order(4, 1, Side.BUY, 9994, 1000).tick(5 * MS);
        assertEquals(List.of("bids[10000x1000 9995x2000 9990x1000] asks[]"), h.views(1));
    }

    @Test
    void offersRoundUpAndDepthIsLimited() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdLevels(2).build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.md.subscribe(2, 0, 0, 1, false, h.sink);
        h.order(1, 1, Side.SELL, 10001, 1000).order(2, 1, Side.SELL, 10003, 1000).order(3, 1, Side.SELL, 10006, 1000)
                .order(4, 1, Side.SELL, 10011, 1000).tick(5 * MS);
        assertEquals(List.of("bids[] asks[10005x2000 10010x1000]"), h.views(1));
        assertEquals(List.of("bids[] asks[10005x2000]"), h.views(2), "top of book");
    }

    @Test
    void hiddenQuantityIsNeverShown() {
        MdHarness h = new MdHarness(MdHarness.fourDp().build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.order(1, 1, Side.BUY, 10000, 5000, 1000).order(2, 1, Side.BUY, 9990, 3000, 0).tick(5 * MS);
        assertEquals(List.of("bids[10000x1000] asks[]"), h.views(1));
    }

    @Test
    void screenedBookShowsOnlyEligibleCounterparties() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdPriceIncrement(1).build()).trade(3, 1);
        h.md.subscribe(1, 3, 0, 0, true, h.sink);  // account 3: eligible with 1 only
        h.md.subscribe(2, 0, 0, 0, false, h.sink); // unscreened
        h.order(1, 1, Side.BUY, 10000, 1000).order(2, 2, Side.BUY, 10001, 1000).order(3, 3, Side.SELL, 10005, 1000)
                .tick(5 * MS);
        assertEquals(List.of("bids[10000x1000] asks[]"), h.views(1), "not 2's bid, not its own offer");
        assertEquals(List.of("bids[10001x1000 10000x1000] asks[10005x1000]"), h.views(2));
    }

    @Test
    void subscribersWithTheSameEligibilityShareOneBuild() {
        MdHarness h = new MdHarness(MdHarness.fourDp().build());
        for (int a = 3; a <= 8; a++) {
            h.trade(a, 1);
        }
        h.order(1, 1, Side.BUY, 10000, 1000).order(2, 2, Side.BUY, 10001, 1000);
        for (int a = 3; a <= 8; a++) {
            h.md.subscribe(a, a, 0, 0, true, h.sink);
        }
        h.tick(5 * MS);
        assertEquals(6, h.published.size());
        assertEquals(1, h.md.builds(), "six subscribers, one equivalence class");
    }

    @Test
    void onlyChangedViewsArePublished() {
        MdHarness h = new MdHarness(MdHarness.fourDp().build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.order(1, 1, Side.BUY, 10000, 1000).tick(5 * MS).tick(10 * MS);
        assertEquals(1, h.views(1).size());
        h.order(2, 1, Side.BUY, 10000, 1000).tick(15 * MS);
        assertEquals(List.of("bids[10000x1000] asks[]", "bids[10000x2000] asks[]"), h.views(1));
    }

    @Test
    void sendOrderRotatesByOneEachCycle() {
        MdHarness h = new MdHarness(MdHarness.fourDp().build());
        for (long s = 1; s <= 3; s++) {
            h.md.subscribe(s, 0, 0, 0, false, h.sink);
        }
        for (int cycle = 0; cycle < 3; cycle++) {
            h.order(10 + cycle, 1, Side.BUY, 10000 - cycle * 10, 1000).tick((cycle + 1) * 5 * MS);
        }
        List<Long> order = h.published.stream().map(MdHarness.Published::subscriptionId).toList();
        assertEquals(List.of(1L, 2L, 3L, 2L, 3L, 1L, 3L, 1L, 2L), order);
    }

    @Test
    void respectsTheInstrumentInterval() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdIntervalNanos(10 * MS).build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.order(1, 1, Side.BUY, 10000, 1000).tick(5 * MS);
        assertTrue(h.published.isEmpty());
        h.tick(10 * MS);
        assertEquals(1, h.published.size());
    }

    @Test
    void snapshotIsACoordinatedCutMatchingTheEngine() {
        MdHarness g = new MdHarness(MdHarness.fourDp().mdPriceIncrement(1).build()).trade(1, 2);
        MatchingEngine engine = g.follower();
        g.md.subscribe(1, 0, 0, 0, false, g.sink);
        g.order(1, 1, Side.BUY, 10000, 3000).order(2, 2, Side.SELL, 10000, 1000).tick(5 * MS);
        g.order(3, 2, Side.SELL, 10000, 1000); // after the cut: not in the 5ms snapshot
        assertEquals(List.of("bids[10000x2000] asks[]"), g.views(1));
        assertEquals(1000, engine.book(0).visibleQtyAt(Side.BUY, 10000), "the engine has moved on");
        assertEquals(engine.book(0).visibleQtyAt(Side.BUY, 10000),
                g.md.replica().book(0).visibleQtyAt(Side.BUY, 10000), "the replica tracks the engine exactly");
    }
}
