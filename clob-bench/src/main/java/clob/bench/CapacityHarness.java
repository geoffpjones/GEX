package clob.bench;

import clob.app.ClobNode;
import clob.app.NodeConfig;
import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.NewOrderCommand;
import clob.marketdata.MarketDataSink;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.Pipeline;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Whole-node capacity run (PLAN M7): a real {@link ClobNode} with sequencer, journal, engine, market data
 * replica, gateway and ops all running, driven through the pipeline at a target rate.
 *
 * <p>Arguments: instruments, accounts, inputs/s (0 = as fast as possible), seconds, screened subscribers per
 * book, publish threads, resting orders per book, limited-credit share in percent. It reports what the
 * node sustained, and how far behind each consumer got. Results are only as meaningful as the host.
 */
public final class CapacityHarness {
    private CapacityHarness() {}

    private static final long MD_INTERVAL_MS = 5;

    public static void main(String[] args) throws Exception {
        int instruments = arg(args, 0, 200);
        int accounts = arg(args, 1, 64);
        long rate = arg(args, 2, 100_000);
        int seconds = arg(args, 3, 60);
        int subscribers = arg(args, 4, 8);
        int publishThreads = arg(args, 5, 1);
        int depth = arg(args, 6, 200);
        int limitedPercent = arg(args, 7, 20);

        Path dir = Files.createTempDirectory("clob-capacity");
        StringBuilder refData = new StringBuilder();
        for (int i = 0; i < instruments; i++) {
            refData.append("instrument ").append(i).append(" EUR/C").append(i).append(" SPOT precision=5 priceIncrement=1")
                    .append(" minQty=1000 qtyIncrement=1000 maxQty=1000000 mdPriceIncrement=1 mdLevels=5 mdIntervalMs=")
                    .append(MD_INTERVAL_MS).append(" minTtlMicros=0\n");
        }
        for (int a = 1; a <= accounts; a++) {
            refData.append("entitle ").append(a).append(' ').append(a).append('\n');
        }
        Files.writeString(dir.resolve("refdata.txt"), refData);
        Properties p = new Properties();
        p.setProperty("accounts.max", String.valueOf(accounts));
        p.setProperty("fix.port", "0");
        p.setProperty("admin.port", "0");
        p.setProperty("admin.token", "x");
        p.setProperty("md.intervalMs", String.valueOf(MD_INTERVAL_MS));
        p.setProperty("md.publishThreads", String.valueOf(publishThreads));
        p.setProperty("eod.schedule", "");
        NodeConfig cfg = NodeConfig.from(p, dir);

        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            Pipeline pipe = node.pipeline();
            UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
            setUp(pipe, buf, instruments, accounts, limitedPercent);
            pipe.drain();

            Histogram arrival = new Histogram(60_000_000_000L, 3);
            AtomicLong delivered = new AtomicLong();
            MarketDataSink sink = (id, boundary, view) -> {
                long late = Math.max(0, System.currentTimeMillis() * 1_000_000L - boundary);
                synchronized (arrival) {
                    arrival.recordValue(Math.min(late, 59_000_000_000L));
                }
                delivered.incrementAndGet();
            };
            long subId = 1;
            for (int i = 0; i < instruments; i++) {
                node.marketData().subscribe(subId++, 0, i, 5, false, sink);
                for (int s = 0; s < subscribers; s++) {
                    node.marketData().subscribe(subId++, 1 + s % accounts, i, 5, true, sink);
                }
            }

            long[][] live = new long[instruments][depth];
            int[] cursor = new int[instruments];
            int[][] owner = new int[instruments][depth];
            NewOrderCommand c = new NewOrderCommand();
            Random r = new Random(7);
            long sent = 0;
            long refused = 0;
            long nextId = 1;
            long maxEngineLag = 0;
            long maxFollowerLag = 0;
            System.gc();
            long heapStart = usedHeap();
            long gcStart = gcMillis();
            long start = System.nanoTime();
            long end = start + seconds * 1_000_000_000L;
            long nextSample = start;
            while (System.nanoTime() < end) {
                if (rate > 0 && sent * 1_000_000_000L / rate > System.nanoTime() - start) {
                    Thread.onSpinWait();
                    continue;
                }
                int book = r.nextInt(instruments);
                int slot = cursor[book];
                if (live[book][slot] != 0 && sent % 2 == 1) {
                    if (!submit(pipe, buf, InputCodec.cancel(buf, 0, live[book][slot], owner[book][slot], CancelSource.PARTICIPANT))) {
                        refused++;
                        continue;
                    }
                    live[book][slot] = 0;
                } else {
                    int account = 1 + r.nextInt(accounts);
                    boolean buy = r.nextBoolean();
                    boolean aggressive = r.nextInt(100) < 10;
                    c.reset();
                    c.orderId = nextId;
                    c.instrumentId = book;
                    c.accountId = account;
                    c.userId = account;
                    c.side = buy ? Side.BUY : Side.SELL;
                    c.type = OrderType.LIMIT;
                    c.tif = aggressive ? TimeInForce.IOC : TimeInForce.GTC;
                    long offset = 1 + r.nextInt(50);
                    c.price = aggressive ? 100_000 + (buy ? offset : -offset) : 100_000 + (buy ? -offset : offset);
                    c.qty = 1000L * (1 + r.nextInt(5));
                    if (!submit(pipe, buf, InputCodec.newOrder(buf, 0, c))) {
                        refused++;
                        continue;
                    }
                    nextId++;
                    if (!aggressive) {
                        live[book][slot] = c.orderId;
                        owner[book][slot] = account;
                        cursor[book] = (slot + 1) % depth;
                    }
                }
                sent++;
                long now = System.nanoTime();
                if (now > nextSample) {
                    maxEngineLag = Math.max(maxEngineLag, pipe.lastSequence() - pipe.processedSequence());
                    maxFollowerLag = Math.max(maxFollowerLag, pipe.lastSequence() - pipe.slowestFollowerSequence());
                    nextSample = now + 10_000_000L;
                }
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            boolean drained = pipe.drain(30_000_000_000L);
            double drainedAt = (System.nanoTime() - start) / 1e9;
            System.gc();
            System.out.printf("config: instruments=%d accounts=%d target=%d/s subscribers/book=%d screened+1 unscreened, publishThreads=%d depth=%d limited=%d%%%n",
                    instruments, accounts, rate, subscribers, publishThreads, depth, limitedPercent);
            System.out.printf("offered: %.0f inputs/s for %.1fs (full ring retries: %d); drained=%s after %.1fs%n", sent / elapsed, elapsed,
                    refused, drained, drainedAt);
            System.out.printf("max lag sampled: engine %d, slowest follower %d inputs%n", maxEngineLag, maxFollowerLag);
            var full = node.marketData().fullCycleNanos();
            var cycle = node.marketData().cycleNanos();
            System.out.printf("md cycle (all books): p50 %.2fms p99 %.2fms max %.2fms; overruns of the %dms tick: %d of %d%n", full.getValueAtPercentile(50) / 1e6,
                    full.getValueAtPercentile(99) / 1e6, full.getMaxValue() / 1e6, MD_INTERVAL_MS, node.marketData().overruns(), full.getTotalCount());
            System.out.printf("md one instrument: p50 %.1fus p99 %.1fus; views built %d, delivered %d, skipped %d%n", cycle.getValueAtPercentile(50) / 1e3,
                    cycle.getValueAtPercentile(99) / 1e3, node.marketData().builds(), delivered.get(), node.marketData().skipped());
            synchronized (arrival) {
                System.out.printf("boundary to delivery (ms clock): p50 %.2fms p99 %.2fms max %.2fms; tick lateness max %.2fms%n", arrival.getValueAtPercentile(50) / 1e6,
                        arrival.getValueAtPercentile(99) / 1e6, arrival.getMaxValue() / 1e6, pipe.maxMarketDataLatenessNanos() / 1e6);
            }
            System.out.printf("heap after GC: start %d MB, end %d MB; GC time in run %d ms; ready=%s, state=%s%n", heapStart >> 20, usedHeap() >> 20,
                    gcMillis() - gcStart, node.state() == ClobNode.State.READY, node.state());
        }
    }

    private static boolean submit(Pipeline pipe, UnsafeBuffer buf, int length) {
        return pipe.submit(buf, 0, length);
    }

    private static void setUp(Pipeline pipe, UnsafeBuffer buf, int instruments, int accounts, int limitedPercent) {
        List<Integer> lengths = new ArrayList<>();
        Random r = new Random(11);
        send(pipe, buf, InputCodec.usdRate(buf, 0, "EUR", CreditEngine.RATE_SCALE));
        for (int i = 0; i < instruments; i++) {
            send(pipe, buf, InputCodec.valueDates(buf, 0, i, 21_000, -1));
        }
        int line = 1;
        for (int a = 1; a <= accounts; a++) {
            send(pipe, buf, InputCodec.creditLine(buf, 0, line, a, CreditType.UNLIMITED, 0));
            int unlimited = line++;
            send(pipe, buf, InputCodec.creditLine(buf, 0, line, a, CreditType.NOP, 5_000_000_000L));
            int limited = line++;
            for (int b = 1; b <= accounts; b++) {
                if (a != b) {
                    send(pipe, buf, InputCodec.creditRelationship(buf, 0, a, b, CreditEngine.ALL_INSTRUMENTS,
                            r.nextInt(100) < limitedPercent ? limited : unlimited));
                }
            }
        }
        lengths.clear();
    }

    private static void send(Pipeline pipe, UnsafeBuffer buf, int length) {
        while (!pipe.submit(buf, 0, length)) {
            Thread.onSpinWait();
        }
    }

    private static int arg(String[] args, int i, int dflt) {
        return args.length > i ? Integer.parseInt(args[i]) : dflt;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, b.getCollectionTime());
        }
        return total;
    }
}
