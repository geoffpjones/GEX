package clob.bench;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.EngineConfig;
import clob.engine.NewOrderCommand;
import clob.marketdata.MarketDataService;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import java.nio.ByteBuffer;
import java.util.Random;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Screened market data cost: one cycle = every instrument × every subscription. Run with
 * {@code --books 200 --accounts 256 --subsPerBook 64 --ordersPerBook 60 --eligible 90}.
 */
public final class MarketDataBench {
    public static void main(String[] args) {
        java.util.Map<String, String> o = new java.util.HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            o.put(args[i].substring(2), args[i + 1]);
        }
        int books = Integer.parseInt(o.getOrDefault("books", "200"));
        int accounts = Integer.parseInt(o.getOrDefault("accounts", "256"));
        int subsPerBook = Integer.parseInt(o.getOrDefault("subsPerBook", "64"));
        int ordersPerBook = Integer.parseInt(o.getOrDefault("ordersPerBook", "60"));
        int eligiblePct = Integer.parseInt(o.getOrDefault("eligible", "90"));
        int cycles = Integer.parseInt(o.getOrDefault("cycles", "200"));
        boolean churn = Boolean.parseBoolean(o.getOrDefault("churn", "false"));

        RefData.Builder rb = RefData.builder();
        for (int b = 0; b < books; b++) {
            rb.add(Instrument.builder(b, "C" + b + "/USD", "SPOT").pricePrecision(5).priceIncrement(1)
                    .mdPriceIncrement(5).mdLevels(5).mdIntervalNanos(0).minQty(1_000_000).qtyIncrement(1_000_000)
                    .maxQty(100_000_000).minTtlNanos(0).build());
        }
        for (int a = 1; a <= accounts; a++) {
            rb.entitle(a, a);
        }
        RefData refData = rb.build();
        MarketDataService md = new MarketDataService(refData, new EngineConfig(64_000, 1_000_000, 16_384, 64, 4096, 1),
                accounts);
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        long[] seq = {0};
        Runnable stamp = () -> InputCodec.stamp(buf, 0, ++seq[0], seq[0]);
        Random r = new Random(1);
        for (int b = 0; b < books; b++) {
            feed(md, buf, stamp, InputCodec.usdRate(buf, 0, "C" + b, CreditEngine.RATE_SCALE));
            feed(md, buf, stamp, InputCodec.valueDates(buf, 0, b, 20_000, -1));
        }
        for (int a = 1; a <= accounts; a++) {
            feed(md, buf, stamp, InputCodec.creditLine(buf, 0, a, a, CreditType.UNLIMITED, 0));
            for (int c = 1; c <= accounts; c++) {
                if (a != c && r.nextInt(100) < eligiblePct) {
                    feed(md, buf, stamp, InputCodec.creditRelationship(buf, 0, a, c, CreditEngine.ALL_INSTRUMENTS, a));
                }
            }
        }
        // Non-crossing books: bids below 100000, offers above.
        NewOrderCommand c = new NewOrderCommand();
        long id = 1;
        for (int b = 0; b < books; b++) {
            for (int k = 0; k < ordersPerBook; k++) {
                c.reset();
                c.orderId = id++;
                c.instrumentId = b;
                c.accountId = 1 + r.nextInt(accounts);
                c.userId = c.accountId;
                c.side = k % 2 == 0 ? Side.BUY : Side.SELL;
                c.type = OrderType.LIMIT;
                c.tif = TimeInForce.GTC;
                c.price = c.side == Side.BUY ? 99_999 - r.nextInt(30) : 100_001 + r.nextInt(30);
                c.qty = 1_000_000L * (1 + r.nextInt(5));
                feed(md, buf, stamp, InputCodec.newOrder(buf, 0, c));
            }
        }
        long[] published = {0};
        long subId = 1;
        for (int b = 0; b < books; b++) {
            for (int s = 0; s < subsPerBook; s++) {
                md.subscribe(subId++, 1 + (s % accounts), b, 0, true, (sid, boundary, view) -> published[0]++);
            }
        }
        Histogram cycle = new Histogram(60_000_000_000L, 3);
        for (int k = 0; k < cycles; k++) {
            // Change one order per book per cycle so views change and get published.
            for (int b = 0; b < books; b++) {
                c.reset();
                c.orderId = id++;
                c.instrumentId = b;
                c.accountId = 1 + r.nextInt(accounts);
                c.userId = c.accountId;
                c.side = Side.BUY;
                c.type = OrderType.LIMIT;
                c.tif = churn ? TimeInForce.GTC : TimeInForce.IOC; // churn: a new best bid every cycle
                c.price = churn ? 99_999 : 99_000;
                c.qty = 1_000_000L;
                feed(md, buf, stamp, InputCodec.newOrder(buf, 0, c));
            }
            long t0 = System.nanoTime();
            feed(md, buf, stamp, InputCodec.mdTick(buf, 0, (k + 1) * 5_000_000L));
            cycle.recordValue(System.nanoTime() - t0);
        }
        System.out.printf("market data: %d books x %d screened subs (%d accounts, %d%% eligible pairs, %d orders/book), %s%n",
                books, subsPerBook, accounts, eligiblePct, ordersPerBook,
                churn ? "every book changes every cycle" : "books unchanged between cycles");
        System.out.printf("full cycle (all books, all subs): p50=%.2f ms p99=%.2f ms max=%.2f ms; builds=%,d publishes=%,d skipped=%,d%n",
                cycle.getValueAtPercentile(50) / 1e6, cycle.getValueAtPercentile(99) / 1e6, cycle.getMaxValue() / 1e6,
                md.builds(), md.publishes(), md.skipped());
    }

    private static void feed(MarketDataService md, UnsafeBuffer buf, Runnable stamp, int length) {
        stamp.run();
        md.onInput(buf, 0, length);
    }
}
