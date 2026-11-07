package clob.bench.jmh;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.NullEngineListener;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Engine microbenchmarks (average ns per operation). Run:
 * {@code java -cp 'clob-bench/build/install/clob-bench/lib/*' org.openjdk.jmh.Main EngineBenchmarks}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"})
public class EngineBenchmarks {

    @State(Scope.Thread)
    public static class Book {
        static final int ACCOUNTS = 64;
        MatchingEngine engine;
        final NewOrderCommand c = new NewOrderCommand();
        long nextId = 1;
        long time = 1;

        @Setup
        public void setup() {
            RefData.Builder b = RefData.builder().add(Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(0)
                    .minQty(1000).qtyIncrement(1000).maxQty(10_000_000).minTtlNanos(0).pegThrottleNanos(20_000)
                    .matchPriority(MatchPriority.VISIBILITY).build());
            for (int a = 1; a <= ACCOUNTS; a++) {
                b.entitle(a, a);
            }
            RefData refData = b.build();
            engine = new MatchingEngine(refData, EngineConfig.defaults(), new CreditEngine(ACCOUNTS, refData),
                    NullEngineListener.INSTANCE);
            engine.onUsdRate("EUR", CreditEngine.RATE_SCALE, 0);
            engine.onValueDates(0, 20_000, -1, 0);
            // Accounts 1..32 trade with each other; 33..64 have no credit with account 1 (the aggressor).
            for (int a = 1; a <= ACCOUNTS; a++) {
                engine.onCreditLine(a, a, CreditType.UNLIMITED, 0, 0);
            }
            for (int a = 1; a <= 32; a++) {
                for (int x = 1; x <= ACCOUNTS; x++) {
                    if (a != x && (x <= 32 || a != 1)) {
                        engine.onCreditRelationship(a, x, CreditEngine.ALL_INSTRUMENTS, a, 0);
                        engine.onCreditRelationship(x, a, CreditEngine.ALL_INSTRUMENTS, x, 0);
                    }
                }
            }
            // A realistic resting book: 20 levels each side, 3 orders per level.
            for (int lvl = 0; lvl < 20; lvl++) {
                for (int k = 0; k < 3; k++) {
                    add(2 + k, Side.BUY, 990 - lvl, 1000, TimeInForce.GTC);
                    add(5 + k, Side.SELL, 1010 + lvl, 1000, TimeInForce.GTC);
                }
            }
        }

        long add(int account, Side side, long price, long qty, TimeInForce tif) {
            c.reset();
            c.orderId = nextId++;
            c.instrumentId = 0;
            c.accountId = account;
            c.userId = account;
            c.side = side;
            c.type = OrderType.LIMIT;
            c.tif = tif;
            c.price = price;
            c.qty = qty;
            engine.onNewOrder(c, ++time);
            return c.orderId;
        }
    }

    /** Passive add then cancel (the dominant market-maker pattern). */
    @Benchmark
    public void addThenCancel(Book b) {
        long id = b.add(9, Side.BUY, 995, 1000, TimeInForce.GTC);
        b.engine.onCancel(id, 9, CancelSource.PARTICIPANT, ++b.time);
    }

    /** Five resting offers, then an IOC that sweeps all five levels (5 adds + 5 fills). */
    @Benchmark
    public void addFiveThenSweep(Book b) {
        for (int k = 0; k < 5; k++) {
            b.add(10 + k, Side.SELL, 1000 + k, 1000, TimeInForce.GTC);
        }
        b.add(1, Side.BUY, 1004, 5000, TimeInForce.IOC);
    }

    /** Crossed book: 30 offers from accounts the aggressor can't trade with sit ahead of one it can. */
    @State(Scope.Thread)
    public static class Crossed extends Book {
        @Setup
        public void crossed() {
            for (int k = 0; k < 30; k++) {
                add(33 + k, Side.SELL, 995 + (k % 3), 1000, TimeInForce.GTC); // ineligible for account 1
            }
        }
    }

    @Benchmark
    public void walkPastIneligible(Crossed b) {
        b.add(12, Side.SELL, 999, 1000, TimeInForce.GTC); // the one eligible offer
        b.add(1, Side.BUY, 1000, 1000, TimeInForce.IOC);
    }

    /** 200 pegs re-pricing on one tick after the reference moves. */
    @State(Scope.Thread)
    public static class Pegs extends Book {
        long tick;
        boolean up;

        @Setup
        public void pegs() {
            for (int k = 0; k < 200; k++) {
                c.reset();
                c.orderId = nextId++;
                c.instrumentId = 0;
                c.accountId = 2 + (k % 30);
                c.userId = c.accountId;
                c.side = k % 2 == 0 ? Side.BUY : Side.SELL;
                c.type = OrderType.PEGGED;
                c.pegReference = PegReference.NEAR_TOUCH;
                c.tif = TimeInForce.GTC;
                c.qty = 1000;
                engine.onNewOrder(c, ++time);
            }
            tick = (time / 20_000 + 1) * 20_000;
        }
    }

    @Benchmark
    public void pegTickReprices200(Pegs b) {
        // Move the best bid by one tick (alternately), then reach the next peg grid tick.
        b.up = !b.up;
        b.add(3, Side.BUY, b.up ? 991 : 990, 1000, TimeInForce.IOC);
        long id = b.add(3, Side.BUY, 991, 1000, TimeInForce.GTC);
        b.time = b.tick;
        b.tick += 20_000;
        b.engine.onTime(b.time);
        b.engine.onCancel(id, 3, CancelSource.ADMIN, ++b.time);
    }
}
