package clob.bench;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import org.HdrHistogram.Histogram;

/**
 * Phase 0 throughput spike: can one engine thread carry 200 books x 10k updates/sec (2M events/sec)?
 *
 * <p>Drives the real {@link MatchingEngine} with synthetic flow from {@link FlowGenerator}. Logical
 * time advances by {@code 1e9 / rate} per event, so min TTL, queued cancels and timers behave as they
 * would at the target rate. Two modes:
 *
 * <ul>
 *   <li><b>saturate</b> ({@code --rate 0}): process as fast as possible. Reports throughput and
 *       per-event service time.
 *   <li><b>paced</b> ({@code --rate N}): event i is due at {@code start + i/N}. Latency is measured
 *       from the due time, so a backlog shows up as latency (coordinated-omission corrected).
 *       Pacing restarts each chunk, after the untimed generation step.
 * </ul>
 *
 * <p>Options: {@code --events N --warmup N --rate N --books N --accounts N --journal PATH|none
 * --credit true|false --timing true|false --priority VISIBILITY|PRICE --mode engine|journal|hiccup
 * --takerRaceMicros N --makerRaceMicros N}.
 */
public final class ThroughputSpike {
    private static final int CHUNK = 65_536;

    public static void main(String[] args) throws IOException {
        Map<String, String> opt = parse(args);
        long events = Long.parseLong(opt.getOrDefault("events", "20000000"));
        long warmup = Long.parseLong(opt.getOrDefault("warmup", "5000000"));
        long rate = Long.parseLong(opt.getOrDefault("rate", "0"));
        int books = Integer.parseInt(opt.getOrDefault("books", "200"));
        int accounts = Integer.parseInt(opt.getOrDefault("accounts", "256"));
        String journalPath = opt.getOrDefault("journal", "none");
        boolean credit = Boolean.parseBoolean(opt.getOrDefault("credit", "true"));
        boolean timing = Boolean.parseBoolean(opt.getOrDefault("timing", "true"));
        MatchPriority priority = MatchPriority.valueOf(opt.getOrDefault("priority", "VISIBILITY"));
        long takerRaceNanos = Long.parseLong(opt.getOrDefault("takerRaceMicros", "0")) * 1_000L;
        long makerRaceNanos = Long.parseLong(opt.getOrDefault("makerRaceMicros", "0")) * 1_000L;
        long logicalStep = 1_000_000_000L / (rate > 0 ? rate : 2_000_000L);

        RefData.Builder rb = RefData.builder();
        for (int b = 0; b < books; b++) {
            rb.add(Instrument.builder(b, "CCY" + b + "/USD", "SPOT")
                    .pricePrecision(5).priceIncrement(1).mdPriceIncrement(1)
                    .minQty(FlowGenerator.MIN_QTY).qtyIncrement(FlowGenerator.MIN_QTY).maxQty(100 * FlowGenerator.MIN_QTY)
                    .minTtlNanos(5_000_000L).rejectOnTtl(false)
                    .matchPriority(priority)
                    .takerRaceNanos(takerRaceNanos).makerRaceNanos(makerRaceNanos).queueCancels(takerRaceNanos > 0)
                    .build());
        }
        for (int a = 1; a <= accounts; a++) {
            rb.entitle(a, a);
        }
        RefData refData = rb.build();
        SpikeListener listener = new SpikeListener();
        MatchingEngine engine = new MatchingEngine(refData, new EngineConfig(64_000, 1_000_000, 16_384, 64, 65_536, 42),
                new CreditEngine(accounts, refData), listener);
        CreditSetup creditSetup = configureCredit(engine, refData, accounts, credit);
        FlowGenerator gen = new FlowGenerator(books, accounts, CHUNK, 7);
        Journal journal = "none".equals(journalPath) ? null : new Journal(journalPath, 256L << 20);

        if ("hiccup".equals(opt.get("mode"))) {
            hiccup(Long.parseLong(opt.getOrDefault("seconds", "10")));
            return;
        }
        if ("journal".equals(opt.get("mode"))) {
            journalOnly(journal == null ? new Journal("journal.dat", 256L << 20) : journal, gen, warmup, events);
            return;
        }
        Driver driver = new Driver(engine, gen, journal, logicalStep);
        System.out.printf("config: events=%,d warmup=%,d rate=%s books=%d accounts=%d journal=%s credit=%s timing=%s priority=%s takerRace=%dus makerRace=%dus%n",
                events, warmup, rate == 0 ? "saturate" : String.format("%,d/s", rate), books, accounts,
                journal != null, credit, timing, priority, takerRaceNanos / 1000, makerRaceNanos / 1000);
        System.out.printf("jvm: %s %s, gc=%s%n", System.getProperty("java.vm.name"), System.getProperty("java.version"),
                gcNames());

        driver.run(warmup, 0, false, null);
        for (Histogram h : driver.byKind) {
            h.reset();
        }

        Histogram hist = new Histogram(3_600_000_000_000L, 3);
        long gcCount0 = gcCount();
        long gcTime0 = gcTime();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long alloc0 = threads.getThreadAllocatedBytes(tid);
        long trades0 = listener.trades;

        long busyNanos = driver.run(events, rate, timing, hist);

        long alloc = threads.getThreadAllocatedBytes(tid) - alloc0;
        long gcCount = gcCount() - gcCount0;
        long gcTime = gcTime() - gcTime0;

        double seconds = busyNanos / 1e9;
        System.out.println();
        System.out.printf("processed %,d events in %.3f s of engine time -> %,.0f events/s (%.1f ns/event mean)%n",
                events, seconds, events / seconds, (double) busyNanos / events);
        if (hist.getTotalCount() > 0) {
            String what = rate == 0 ? "service time per event" : "latency from scheduled time (CO-corrected)";
            System.out.printf("%s (ns): p50=%,d p90=%,d p99=%,d p99.9=%,d p99.99=%,d max=%,d%n", what,
                    hist.getValueAtPercentile(50), hist.getValueAtPercentile(90), hist.getValueAtPercentile(99),
                    hist.getValueAtPercentile(99.9), hist.getValueAtPercentile(99.99), hist.getMaxValue());
        }
        String[] kinds = {"passive add", "aggressive IOC", "cancel", "replace"};
        for (int k = 0; k < kinds.length; k++) {
            Histogram h = driver.byKind[k];
            if (h.getTotalCount() > 0) {
                System.out.printf("  %-15s %5.1f%% of events: mean=%,.0f p50=%,d p99=%,d p99.9=%,d ns%n", kinds[k],
                        100.0 * h.getTotalCount() / hist.getTotalCount(), h.getMean(), h.getValueAtPercentile(50),
                        h.getValueAtPercentile(99), h.getValueAtPercentile(99.9));
            }
        }
        System.out.printf("allocation on engine thread: %,d bytes (%.3f bytes/event); GCs during run: %d (%d ms)%n",
                alloc, (double) alloc / events, gcCount, gcTime);
        System.out.printf("outputs: accepted=%,d trades=%,d (%,d in run) cancels=%,d replaced=%,d queued=%,d requestRejects=%,d rejected=%,d%n",
                listener.accepted, listener.trades, listener.trades - trades0, listener.cancels, listener.replaced,
                listener.queued, listener.requestRejects, listener.rejected);
        System.out.println("credit: " + creditSetup);
        int depth = 0;
        for (int b = 0; b < books; b++) {
            depth += engine.book(b).levelCount(Side.BUY) + engine.book(b).levelCount(Side.SELL);
        }
        System.out.printf("book state: %,d live orders, %.1f price levels per book%n", engine.liveOrderCount(),
                (double) depth / books);
    }

    record CreditSetup(int none, int unlimited, int dsl, int nop) {
        @Override
        public String toString() {
            int total = none + unlimited + dsl + nop;
            return String.format("%,d directed pairs: %d%% none, %d%% unlimited, %d%% DSL, %d%% NOP", total,
                    100 * none / total, 100 * unlimited / total, 100 * dsl / total, 100 * nop / total);
        }
    }

    /**
     * {@code realistic}: per directed pair, 10% no credit, 60% unlimited (one shared line per giver),
     * 20% DSL (USD 50–500m), 10% NOP (USD 100m–1bn). Otherwise unlimited between every pair.
     */
    private static CreditSetup configureCredit(MatchingEngine engine, RefData refData, int accounts, boolean realistic) {
        for (Instrument i : refData.instruments()) {
            engine.onUsdRate(i.baseCurrency(), CreditEngine.RATE_SCALE, 0);
            engine.onValueDates(i.id(), 20_000, -1, 0);
        }
        int none = 0;
        int unlimited = 0;
        int dsl = 0;
        int nop = 0;
        int nextLine = accounts + 1;
        long r = 12345;
        for (int a = 1; a <= accounts; a++) {
            engine.onCreditLine(a, a, CreditType.UNLIMITED, 0, 0);
            for (int b = 1; b <= accounts; b++) {
                if (a == b) {
                    continue;
                }
                r = r * 6364136223846793005L + 1442695040888963407L;
                int roll = realistic ? (int) ((r >>> 33) % 100) : 50;
                if (roll < 10) {
                    none++;
                } else if (roll < 70) {
                    engine.onCreditRelationship(a, b, CreditEngine.ALL_INSTRUMENTS, a, 0);
                    unlimited++;
                } else if (roll < 90) {
                    long limit = 50_000_000L * (1 + (r >>> 40) % 10);
                    engine.onCreditLine(nextLine, a, CreditType.DSL, limit, 0);
                    engine.onCreditRelationship(a, b, CreditEngine.ALL_INSTRUMENTS, nextLine++, 0);
                    dsl++;
                } else {
                    long limit = 100_000_000L * (1 + (r >>> 40) % 10);
                    engine.onCreditLine(nextLine, a, CreditType.NOP, limit, 0);
                    engine.onCreditRelationship(a, b, CreditEngine.ALL_INSTRUMENTS, nextLine++, 0);
                    nop++;
                }
            }
        }
        return new CreditSetup(none, unlimited, dsl, nop);
    }

    /** Feeds generated chunks into the engine, optionally journalling each input first. */
    private static final class Driver {
        private final MatchingEngine engine;
        private final FlowGenerator gen;
        private final Journal journal;
        private final long logicalStep;
        private final NewOrderCommand order = new NewOrderCommand();
        private final ReplaceCommand replace = new ReplaceCommand();
        private long logicalTime;
        private long sink;
        final Histogram[] byKind = {
            new Histogram(3_600_000_000_000L, 3), new Histogram(3_600_000_000_000L, 3),
            new Histogram(3_600_000_000_000L, 3), new Histogram(3_600_000_000_000L, 3),
        };

        Driver(MatchingEngine engine, FlowGenerator gen, Journal journal, long logicalStep) {
            this.engine = engine;
            this.gen = gen;
            this.journal = journal;
            this.logicalStep = logicalStep;
        }

        /** Returns nanoseconds spent processing (generation excluded). */
        long run(long events, long rate, boolean timing, Histogram hist) {
            long busy = 0;
            long remaining = events;
            long interval = rate > 0 ? 1_000_000_000L / rate : 0;
            while (remaining > 0) {
                gen.fill();
                int n = (int) Math.min(remaining, gen.chunk);
                long start = System.nanoTime();
                if (rate > 0) {
                    for (int i = 0; i < n; i++) {
                        long due = start + i * interval;
                        while (System.nanoTime() < due) {
                            Thread.onSpinWait();
                        }
                        apply(i);
                        hist.recordValue(Math.max(0, System.nanoTime() - due));
                    }
                } else if (timing) {
                    for (int i = 0; i < n; i++) {
                        long t0 = System.nanoTime();
                        apply(i);
                        long d = System.nanoTime() - t0;
                        hist.recordValue(d);
                        byKind[gen.kind[i]].recordValue(d);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        apply(i);
                    }
                }
                busy += System.nanoTime() - start;
                remaining -= n;
            }
            if (sink == 42) {
                System.out.print("");
            }
            return busy;
        }

        private void apply(int i) {
            logicalTime += logicalStep;
            long id = gen.orderId[i];
            int account = FlowGenerator.accountOf(id, gen.accounts());
            if (journal != null) {
                journal.append(gen.kind[i], gen.instrument[i], id, gen.price[i], gen.qty[i], logicalTime);
            }
            switch (gen.kind[i]) {
                case FlowGenerator.NEW_PASSIVE, FlowGenerator.NEW_AGGRESSIVE -> {
                    NewOrderCommand c = order;
                    c.orderId = id;
                    c.instrumentId = gen.instrument[i];
                    c.accountId = account;
                    c.userId = account;
                    c.side = gen.buy[i] ? Side.BUY : Side.SELL;
                    c.price = gen.price[i];
                    c.qty = gen.qty[i];
                    c.expireTime = 0;
                    if (gen.kind[i] == FlowGenerator.NEW_AGGRESSIVE) {
                        c.type = OrderType.LIMIT;
                        c.tif = TimeInForce.IOC;
                        c.maxShow = NewOrderCommand.FULLY_VISIBLE;
                    } else {
                        long show = gen.show[i];
                        c.type = show > 0 ? OrderType.ICEBERG : OrderType.LIMIT;
                        c.tif = TimeInForce.DAY;
                        c.maxShow = show;
                    }
                    engine.onNewOrder(c, logicalTime);
                }
                case FlowGenerator.CANCEL -> engine.onCancel(id, account, CancelSource.PARTICIPANT, logicalTime);
                case FlowGenerator.REPLACE -> {
                    ReplaceCommand r = replace;
                    r.orderId = id;
                    r.userId = account;
                    r.price = gen.price[i];
                    r.qty = gen.qty[i];
                    r.maxShow = NewOrderCommand.FULLY_VISIBLE;
                    engine.onReplace(r, logicalTime);
                }
                default -> throw new IllegalStateException();
            }
            sink += id;
        }
    }

    /** Fixed-size input records appended to a memory-mapped file that wraps (stand-in for Aeron Archive). */
    private static final class Journal {
        private static final int RECORD = 48;
        private final MappedByteBuffer buffer;
        private final int capacity;
        private int position;

        Journal(String path, long bytes) throws IOException {
            try (RandomAccessFile file = new RandomAccessFile(path, "rw"); FileChannel ch = file.getChannel()) {
                buffer = ch.map(FileChannel.MapMode.READ_WRITE, 0, bytes);
            }
            capacity = (int) Math.min(bytes, Integer.MAX_VALUE) / RECORD * RECORD;
            for (int p = 0; p < capacity; p += 4096) {
                buffer.put(p, (byte) 0); // pre-touch so page faults happen at startup, not on the hot path
            }
        }

        void append(byte kind, int instrument, long id, long price, long qty, long time) {
            if (position == capacity) {
                position = 0;
            }
            int p = position;
            buffer.put(p, kind);
            buffer.putInt(p + 4, instrument);
            buffer.putLong(p + 8, id);
            buffer.putLong(p + 16, price);
            buffer.putLong(p + 24, qty);
            buffer.putLong(p + 32, time);
            buffer.putLong(p + 40, 0xC10BL);
            position += RECORD;
        }
    }

    /**
     * Platform baseline: a spin loop doing no work, recording the gap between consecutive clock reads.
     * Any large gap is the OS, hypervisor or JVM taking the core away, not the engine.
     */
    private static void hiccup(long seconds) {
        Histogram h = new Histogram(3_600_000_000_000L, 3);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long prev = System.nanoTime();
        while (prev < end) {
            long t = System.nanoTime();
            h.recordValue(t - prev);
            prev = t;
        }
        System.out.printf("hiccup baseline over %d s (gap between clock reads, ns): p50=%,d p99=%,d p99.9=%,d p99.99=%,d max=%,d; gaps >10us: %,d, >100us: %,d, >1ms: %,d%n",
                seconds, h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getValueAtPercentile(99.9),
                h.getValueAtPercentile(99.99), h.getMaxValue(), h.getCountBetweenValues(10_000, Long.MAX_VALUE / 4),
                h.getCountBetweenValues(100_000, Long.MAX_VALUE / 4), h.getCountBetweenValues(1_000_000, Long.MAX_VALUE / 4));
    }

    /** Journal stage on its own: how fast can one thread append input records? */
    private static void journalOnly(Journal journal, FlowGenerator gen, long warmup, long events) {
        long busy = 0;
        for (long done = -warmup; done < events; done += gen.chunk) {
            gen.fill();
            long t0 = System.nanoTime();
            for (int i = 0; i < gen.chunk; i++) {
                journal.append(gen.kind[i], gen.instrument[i], gen.orderId[i], gen.price[i], gen.qty[i], done + i);
            }
            if (done >= 0) {
                busy += System.nanoTime() - t0;
            }
        }
        System.out.printf("journal only: %,d records in %.3f s -> %,.0f records/s (%.1f ns/record, %.0f MB/s)%n",
                events, busy / 1e9, events / (busy / 1e9), (double) busy / events,
                events * (double) Journal.RECORD / (busy / 1e9) / 1e6);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --name value pairs, got " + args[i]);
            }
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    private static long gcTime() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionTime());
        }
        return n;
    }

    private static String gcNames() {
        StringBuilder sb = new StringBuilder();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append(sb.isEmpty() ? "" : ",").append(gc.getName());
        }
        return sb.toString();
    }
}
