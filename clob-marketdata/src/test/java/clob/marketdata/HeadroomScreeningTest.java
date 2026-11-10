package clob.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.EngineConfig;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.InputCodec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/** Q6e: a screened book shows only quantity the subscriber could trade now. Also R15's late-tick rule and Q42. */
class HeadroomScreeningTest {
    static final long MS = 1_000_000L;
    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));

    private void line(MdHarness h, int lineId, int giver, CreditType type, long limit, int to) {
        h.line(lineId, giver, type, limit, to);
    }

    /** Subscriber account 1 has DSL lines with a counterparty; that side is unlimited. */
    private MdHarness limited(long limit) {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdIntervalNanos(0).build());
        line(h, 10, 1, CreditType.DSL, limit, 2);
        line(h, 11, 2, CreditType.UNLIMITED, 0, 1);
        line(h, 12, 1, CreditType.UNLIMITED, 0, 3);
        line(h, 13, 3, CreditType.UNLIMITED, 0, 1);
        return h;
    }

    @Test
    void quantityIsClippedToRemainingHeadroom() {
        MdHarness h = limited(1500);
        h.md.subscribe(1, 1, 0, 0, true, h.sink);
        h.order(1, 2, Side.BUY, 10000, 3000).tick(5 * MS);
        assertEquals(List.of("bids[10000x1000] asks[]"), h.views(1), "1500 of credit rounds down to a 1000 lot");
    }

    @Test
    void headroomIsSharedAcrossOneCounterpartysOrders() {
        MdHarness h = limited(3000);
        h.md.subscribe(1, 1, 0, 0, true, h.sink);
        h.order(1, 2, Side.BUY, 10000, 2000).order(2, 2, Side.BUY, 9995, 2000).tick(5 * MS);
        assertEquals(List.of("bids[10000x2000 9995x1000] asks[]"), h.views(1));
    }

    @Test
    void exhaustedLineHidesTheOrder() {
        MdHarness h = limited(0);
        h.md.subscribe(1, 1, 0, 0, true, h.sink);
        h.order(1, 2, Side.BUY, 10000, 1000).order(2, 3, Side.BUY, 10000, 1000).tick(5 * MS);
        assertEquals(List.of("bids[10000x1000] asks[]"), h.views(1), "only the unlimited counterparty shows");
    }

    @Test
    void viewsDifferBySide() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdIntervalNanos(0).build());
        line(h, 10, 1, CreditType.NOP, 1000, 2);
        line(h, 11, 2, CreditType.UNLIMITED, 0, 1);
        line(h, 12, 3, CreditType.UNLIMITED, 0, 2); // account 3 gives 2 a line used to open a position
        h.order(1, 2, Side.SELL, 10000, 1000).order(2, 1, Side.BUY, 10000, 1000); // account 1 is now long 1000: at its limit
        h.md.subscribe(1, 1, 0, 0, true, h.sink);
        h.order(3, 2, Side.BUY, 9990, 2000).order(4, 2, Side.SELL, 10010, 2000).tick(5 * MS);
        assertEquals(List.of("bids[9990x2000] asks[]"), h.views(1), "it can sell to reduce, but cannot buy more");
    }

    @Test
    void aTradeInAnotherInstrumentUpdatesTheScreenedBook() {
        Instrument a = MdHarness.fourDp().mdIntervalNanos(0).build();
        Instrument b = Instrument.builder(1, "EUR/GBP", "SPOT").pricePrecision(4).priceIncrement(1).mdPriceIncrement(5)
                .minQty(1000).qtyIncrement(1000).maxQty(1_000_000).minTtlNanos(0).mdLevels(5).mdIntervalNanos(0).build();
        RefData.Builder rb = RefData.builder().add(a).add(b);
        for (int i = 1; i <= 8; i++) {
            rb.entitle(i, i);
        }
        RefData refData = rb.build();
        MarketDataService md = new MarketDataService(refData, EngineConfig.defaults(), 8);
        List<String> views = new ArrayList<>();
        long[] seq = {0};
        Consumer<Integer> feed = n -> {
            InputCodec.stamp(buf, 0, ++seq[0], seq[0]);
            md.onInput(buf, 0, n);
        };
        feed.accept(InputCodec.usdRate(buf, 0, "EUR", CreditEngine.RATE_SCALE));
        feed.accept(InputCodec.valueDates(buf, 0, 0, 20_000, -1));
        feed.accept(InputCodec.valueDates(buf, 0, 1, 20_000, -1));
        feed.accept(InputCodec.creditLine(buf, 0, 1, 1, CreditType.DSL, 1000));
        feed.accept(InputCodec.creditRelationship(buf, 0, 1, 2, CreditEngine.ALL_INSTRUMENTS, 1));
        feed.accept(InputCodec.creditLine(buf, 0, 2, 2, CreditType.UNLIMITED, 0));
        feed.accept(InputCodec.creditRelationship(buf, 0, 2, 1, CreditEngine.ALL_INSTRUMENTS, 2));
        clob.engine.NewOrderCommand c = new clob.engine.NewOrderCommand();
        Consumer<long[]> order = o -> {
            c.reset();
            c.orderId = o[0];
            c.instrumentId = (int) o[1];
            c.accountId = (int) o[2];
            c.userId = (int) o[2];
            c.side = o[3] == 0 ? Side.BUY : Side.SELL;
            c.type = clob.refdata.OrderType.LIMIT;
            c.tif = clob.refdata.TimeInForce.GTC;
            c.price = 10000;
            c.qty = 1000;
            feed.accept(InputCodec.newOrder(buf, 0, c));
        };
        md.subscribe(1, 1, 0, 0, true, (id, boundary, view) -> views.add(view.toString()));
        order.accept(new long[] {1, 0, 2, 0}); // a bid in instrument 0, against which account 1 has 1000 of credit
        feed.accept(InputCodec.mdTick(buf, 0, 5 * MS));
        // A trade in instrument 1 uses that same line. Instrument 0's book has not changed at all.
        order.accept(new long[] {2, 1, 2, 0});
        order.accept(new long[] {3, 1, 1, 1});
        feed.accept(InputCodec.mdTick(buf, 0, 10 * MS));
        assertEquals(List.of("bids[10000x1000] asks[]", "bids[] asks[]"), views);
    }

    @Test
    void aLateTickDoesNotPostponeAnInstrument() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdIntervalNanos(10 * MS).build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink);
        h.order(1, 1, Side.BUY, 10000, 1000).tick(10 * MS);
        h.order(2, 1, Side.BUY, 9995, 1000).tick(15 * MS); // no grid point since 10
        assertEquals(1, h.views(1).size());
        h.tick(25 * MS); // boundary 20 was skipped by the sequencer; 25 is still past it
        assertEquals(2, h.views(1).size(), "the skipped grid point is honoured at the next tick");
    }

    @Test
    void anUnchangedViewIsResentOnTheRefreshInterval() {
        MdHarness h = new MdHarness(MdHarness.fourDp().mdIntervalNanos(0).build());
        h.md.subscribe(1, 0, 0, 0, false, h.sink, 3);
        h.order(1, 1, Side.BUY, 10000, 1000);
        for (int k = 1; k <= 7; k++) {
            h.tick(k * 5 * MS);
        }
        assertEquals(3, h.views(1).size(), "first send, then every third cycle");
    }

    @Test
    void parallelPublishingProducesTheSameViews() throws Exception {
        List<List<String>> runs = new ArrayList<>();
        for (int threads : new int[] {1, 4}) {
            int instruments = 32;
            RefData.Builder rb = RefData.builder();
            for (int i = 0; i < instruments; i++) {
                rb.add(Instrument.builder(i, "EUR/C" + i, "SPOT").pricePrecision(4).priceIncrement(1).mdPriceIncrement(1)
                        .minQty(1000).qtyIncrement(1000).maxQty(1_000_000).minTtlNanos(0).mdLevels(5).mdIntervalNanos(0).build());
            }
            for (int a = 1; a <= 8; a++) {
                rb.entitle(a, a);
            }
            RefData refData = rb.build();
            MarketDataService md = new MarketDataService(refData, EngineConfig.defaults(), 8, threads);
            List<String> views = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < instruments; i++) {
                md.subscribe(i + 1L, 1, i, 0, true, (id, boundary, view) -> views.add(id + "@" + boundary + ":" + view));
            }
            long[] seq = {0};
            Consumer<Integer> feed = n -> {
                InputCodec.stamp(buf, 0, ++seq[0], seq[0]);
                md.onInput(buf, 0, n);
            };
            feed.accept(InputCodec.usdRate(buf, 0, "EUR", CreditEngine.RATE_SCALE));
            for (int i = 0; i < instruments; i++) {
                feed.accept(InputCodec.valueDates(buf, 0, i, 20_000, -1));
            }
            for (int a = 1; a <= 2; a++) {
                feed.accept(InputCodec.creditLine(buf, 0, a, a, CreditType.DSL, 5000));
                feed.accept(InputCodec.creditRelationship(buf, 0, a, 3 - a, CreditEngine.ALL_INSTRUMENTS, a));
            }
            clob.engine.NewOrderCommand c = new clob.engine.NewOrderCommand();
            long id = 1;
            for (int cycle = 1; cycle <= 20; cycle++) {
                for (int i = 0; i < instruments; i++) {
                    c.reset();
                    c.orderId = id++;
                    c.instrumentId = i;
                    c.accountId = 2;
                    c.userId = 2;
                    c.side = Side.BUY;
                    c.type = clob.refdata.OrderType.LIMIT;
                    c.tif = clob.refdata.TimeInForce.GTC;
                    c.price = 9000 + cycle;
                    c.qty = 1000;
                    feed.accept(InputCodec.newOrder(buf, 0, c));
                }
                feed.accept(InputCodec.mdTick(buf, 0, cycle * 5 * MS));
            }
            md.close();
            List<String> sorted = new ArrayList<>(views);
            java.util.Collections.sort(sorted);
            runs.add(sorted);
        }
        assertTrue(runs.get(0).size() > 100);
        assertEquals(runs.get(0), runs.get(1));
    }
}
