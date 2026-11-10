package clob.marketdata;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.engine.NullEngineListener;
import clob.engine.OrderBook;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.EngineDispatcher;
import clob.sequencer.InputCodec;
import clob.sequencer.InputHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;

/**
 * Market data. Consumes the sequenced input stream (as a {@link clob.sequencer.Pipeline} follower) into a
 * <b>replica engine</b>: determinism makes its books and credit state identical to the matching engine's at
 * every sequence number, with no extra engine output. Each {@link InputCodec#MD_TICK} is a cycle boundary
 * at the same point in the stream for every subscriber (a coordinated cut): inputs sequenced before the
 * tick are in the snapshot, inputs after it are not.
 *
 * <p>At a boundary, for each instrument with at least one point of its market data grid since its last
 * cycle (so a late, skipped global tick cannot postpone it):
 * <ul>
 *   <li>collect displayed orders (hidden quantity never shown);
 *   <li>bucket by the market data price increment: bids round down, offers round up (confirmed), up to the
 *       instrument's levels;
 *   <li>unscreened: one view for all. Screened: only quantity the subscriber could trade now. Orders are
 *       walked best first, each clipped to the remaining headroom shared across that counterparty's
 *       orders and any shared pool, so a view of the bids and a view of the offers differ as the lines do.
 *       Subscribers with only unlimited lines among the accounts present share one computation;
 *   <li>publish only views that changed since that subscriber's last update, in a send order that rotates
 *       by one each cycle per instrument (the first subscriber in one cycle is last in the next).
 * </ul>
 *
 * <p>With more than one publish thread, instruments are built and delivered in parallel. The replica is
 * frozen for the cycle because this thread applies no further input until every instrument is done.
 * Sinks must be thread-safe. If a cycle overruns the tick it is counted ({@link #overruns()}); the
 * sequencer then skips to the latest boundary, so an old snapshot is superseded, not queued.
 *
 * <p>Subscription changes may come from any thread; they apply at the next input.
 */
public final class MarketDataService implements InputHandler, AutoCloseable {
    private final RefData refData;
    private final MatchingEngine replica;
    private final EngineDispatcher dispatcher;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Map<Long, Subscription> byId = new HashMap<>();
    private final List<List<Subscription>> byInstrument = new ArrayList<>();
    private final long[] cycles;
    /** Book and credit versions at each instrument's last cycle; a new subscriber forces a cycle. */
    private final long[] lastBookVersion;
    private final long[] lastCreditVersion;
    private final boolean[] dirty;
    private final long[] lastFingerprint;
    private final long[] lastBoundary;
    private final AtomicLong skipped = new AtomicLong();
    /** Per instrument: rows[account] = bitset of accounts it may trade with (bilateral, excluding its own entity). */
    private final long[][][] rows;
    /** Per instrument: limitedRows[account] = bitset of those counterparties with a limited line in either direction. */
    private final long[][][] limitedRows;
    private final long[] rowsVersion;
    private final int words;
    private final int maxAccounts;
    private final Histogram cycleNanos = new Histogram(60_000_000_000L, 3);
    private final Histogram fullCycleNanos = new Histogram(60_000_000_000L, 3);
    private final AtomicLong builds = new AtomicLong();
    private final AtomicLong publishes = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();
    private long tickIntervalNanos;
    private long previousTick = Long.MIN_VALUE;

    private final Worker[] workers;
    private final Thread[] threads;
    private final int[] taskIds;
    private volatile int taskCount;
    private final long[] taskBoundary;
    private final AtomicInteger nextTask = new AtomicInteger();
    private final AtomicInteger pendingWorkers = new AtomicInteger();
    private final AtomicLong cycleGate = new AtomicLong();
    private volatile boolean closed;
    private volatile Throwable workerFailure;

    static final class Subscription {
        final long id;
        final int account;
        final int instrumentId;
        final int depth;
        final boolean screened;
        final MarketDataSink sink;
        final int refreshCycles;
        final BookView current = new BookView(16);
        final BookView last = new BookView(16);
        boolean published;
        int sinceSend;

        Subscription(long id, int account, int instrumentId, int depth, boolean screened, MarketDataSink sink, int refreshCycles) {
            this.id = id;
            this.account = account;
            this.instrumentId = instrumentId;
            this.depth = depth;
            this.screened = screened;
            this.sink = sink;
            this.refreshCycles = refreshCycles;
        }
    }

    private static final class Orders {
        long[] price = new long[256];
        long[] qty = new long[256];
        int[] account = new int[256];
        int n;

        void add(long p, long q, int a) {
            if (n == price.length) {
                price = Arrays.copyOf(price, n * 2);
                qty = Arrays.copyOf(qty, n * 2);
                account = Arrays.copyOf(account, n * 2);
            }
            price[n] = p;
            qty[n] = q;
            account[n] = a;
            n++;
        }
    }

    /** Screening class key: pooled and reused every cycle (the class map is cleared first). */
    private static final class Key {
        final long[] bits;
        int hash;

        Key(int words) {
            bits = new long[words];
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && hash == k.hash && Arrays.equals(bits, k.bits);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public MarketDataService(RefData refData, EngineConfig config, int maxAccounts) {
        this(refData, config, maxAccounts, 1);
    }

    /** {@code publishThreads} above 1 starts that many threads to build and deliver instruments in parallel. */
    public MarketDataService(RefData refData, EngineConfig config, int maxAccounts, int publishThreads) {
        if (publishThreads < 1) {
            throw new IllegalArgumentException("publishThreads must be >= 1");
        }
        this.refData = refData;
        this.replica = new MatchingEngine(refData, config, new CreditEngine(maxAccounts, refData),
                NullEngineListener.INSTANCE);
        this.dispatcher = new EngineDispatcher(replica, (s, t, ty, r) -> { });
        int capacity = refData.instrumentCapacity();
        for (int i = 0; i < capacity; i++) {
            byInstrument.add(new ArrayList<>());
        }
        cycles = new long[capacity];
        lastBookVersion = new long[capacity];
        lastCreditVersion = new long[capacity];
        dirty = new boolean[capacity];
        lastFingerprint = new long[capacity];
        lastBoundary = new long[capacity];
        Arrays.fill(lastBookVersion, -1);
        Arrays.fill(lastBoundary, Long.MIN_VALUE);
        rows = new long[capacity][][];
        limitedRows = new long[capacity][][];
        rowsVersion = new long[capacity];
        Arrays.fill(rowsVersion, -1);
        words = (maxAccounts + 1 + 63) / 64;
        this.maxAccounts = maxAccounts;
        taskIds = new int[capacity];
        taskBoundary = new long[capacity];
        workers = new Worker[publishThreads];
        for (int i = 0; i < publishThreads; i++) {
            workers[i] = new Worker();
        }
        threads = new Thread[publishThreads - 1];
        for (int i = 0; i < threads.length; i++) {
            Worker worker = workers[i + 1];
            threads[i] = new Thread(() -> runWorker(worker), "clob-md-publisher-" + (i + 1));
            threads[i].setDaemon(true);
            threads[i].start();
        }
    }

    private void refreshEligibility(int instrumentId) {
        long version = replica.credit().version();
        if (rows[instrumentId] != null && rowsVersion[instrumentId] == version) {
            return;
        }
        long[][] r = rows[instrumentId] == null ? new long[maxAccounts + 1][words] : rows[instrumentId];
        long[][] l = limitedRows[instrumentId] == null ? new long[maxAccounts + 1][words] : limitedRows[instrumentId];
        CreditEngine credit = replica.credit();
        for (int a = 1; a <= maxAccounts; a++) {
            Arrays.fill(r[a], 0);
            Arrays.fill(l[a], 0);
            for (int b = 1; b <= maxAccounts; b++) {
                if (credit.eligible(a, b, instrumentId) && !credit.sameEntity(a, b)) {
                    r[a][b >>> 6] |= 1L << (b & 63);
                    if (credit.limited(a, b, instrumentId)) {
                        l[a][b >>> 6] |= 1L << (b & 63);
                    }
                }
            }
        }
        rows[instrumentId] = r;
        limitedRows[instrumentId] = l;
        rowsVersion[instrumentId] = version;
    }

    /** Instrument cycles skipped because neither the book nor credit changed. */
    public long skipped() {
        return skipped.get();
    }

    /** Cycles that took longer than the market data tick, so a later boundary may have been skipped. */
    public long overruns() {
        return overruns.get();
    }

    /**
     * Subscribes (any thread). {@code depth} 1 = top of book; 0 = all of the instrument's levels.
     * {@code screened} requires {@code account}.
     */
    public void subscribe(long id, int account, int instrumentId, int depth, boolean screened, MarketDataSink sink) {
        subscribe(id, account, instrumentId, depth, screened, sink, 0);
    }

    /**
     * As above, but also re-sends the current view at least every {@code refreshCycles} instrument cycles
     * even if unchanged, so a receiver of a lossy transport recovers from a lost final update. 0 = never.
     */
    public void subscribe(long id, int account, int instrumentId, int depth, boolean screened, MarketDataSink sink, int refreshCycles) {
        commands.add(() -> {
            Instrument instrument = refData.instrument(instrumentId);
            int levels = depth <= 0 ? instrument.mdLevels() : Math.min(depth, instrument.mdLevels());
            Subscription s = new Subscription(id, account, instrumentId, levels, screened, sink, refreshCycles);
            byId.put(id, s);
            byInstrument.get(instrumentId).add(s);
            dirty[instrumentId] = true;
        });
    }

    public void unsubscribe(long id) {
        commands.add(() -> {
            Subscription s = byId.remove(id);
            if (s != null) {
                byInstrument.get(s.instrumentId).remove(s);
            }
        });
    }

    /** Replica state, for tests and diagnostics (market data thread only). */
    public MatchingEngine replica() {
        return replica;
    }

    /** Time to build and hand off one instrument's cycle, in nanoseconds. */
    public Histogram cycleNanos() {
        return cycleNanos;
    }

    /** Time for one whole boundary across every instrument, in nanoseconds. */
    public Histogram fullCycleNanos() {
        return fullCycleNanos;
    }

    /** Views computed so far (unscreened books plus one per screened class or per limited subscriber). */
    public long builds() {
        return builds.get();
    }

    public long publishes() {
        return publishes.get();
    }

    @Override
    public void onInput(DirectBuffer buffer, int offset, int length) {
        Runnable c;
        while ((c = commands.poll()) != null) {
            c.run();
        }
        dispatcher.onInput(buffer, offset, length);
        if (InputCodec.type(buffer, offset) == InputCodec.MD_TICK) {
            onBoundary(InputCodec.mdTickBoundary(buffer, offset));
        }
    }

    private void onBoundary(long boundary) {
        long start = System.nanoTime();
        int count = 0;
        for (int id = 0; id < byInstrument.size(); id++) {
            List<Subscription> subs = byInstrument.get(id);
            if (subs.isEmpty()) {
                continue;
            }
            Instrument instrument = refData.instrument(id);
            long interval = instrument.mdIntervalNanos();
            if (interval > 0 && (lastBoundary[id] == Long.MIN_VALUE ? Math.floorMod(boundary, interval) != 0
                    : Math.floorDiv(boundary, interval) == Math.floorDiv(lastBoundary[id], interval))) {
                continue; // no point of this instrument's grid since its last cycle
            }
            lastBoundary[id] = boundary;
            boolean screened = false;
            boolean refreshing = false;
            for (int k = 0; k < subs.size(); k++) {
                Subscription s = subs.get(k);
                screened |= s.screened;
                refreshing |= s.refreshCycles > 0 && s.sinceSend + 1 >= s.refreshCycles;
            }
            long bookVersion = replica.book(id).version();
            long creditVersion = screened ? replica.credit().usageVersion() : 0;
            boolean creditSame = creditVersion == lastCreditVersion[id];
            if (!dirty[id] && !refreshing && bookVersion == lastBookVersion[id] && creditSame) {
                skipped.incrementAndGet(); // nothing anyone can see has changed (the rotation still advances)
                advanceRefresh(subs);
                cycles[id]++;
                continue;
            }
            lastBookVersion[id] = bookVersion;
            lastCreditVersion[id] = creditVersion;
            if (screened) {
                refreshEligibility(id);
            }
            taskIds[count] = id;
            taskBoundary[count] = boundary;
            count++;
        }
        if (count > 0) {
            runTasks(count);
        }
        long elapsed = System.nanoTime() - start;
        fullCycleNanos.recordValue(elapsed);
        if (previousTick != Long.MIN_VALUE) {
            tickIntervalNanos = boundary - previousTick;
            if (tickIntervalNanos > 0 && elapsed > tickIntervalNanos) {
                overruns.incrementAndGet();
            }
        }
        previousTick = boundary;
    }

    private static void advanceRefresh(List<Subscription> subs) {
        for (int k = 0; k < subs.size(); k++) {
            subs.get(k).sinceSend++;
        }
    }

    private void runTasks(int count) {
        for (Worker w : workers) {
            replica.credit().prepare(w.overlay);
        }
        taskCount = count;
        nextTask.set(0);
        pendingWorkers.set(threads.length);
        cycleGate.incrementAndGet(); // the volatile write that releases the workers
        drainTasks(workers[0]);
        while (pendingWorkers.get() > 0) {
            Thread.onSpinWait();
        }
        if (workerFailure != null) {
            throw new IllegalStateException("Market data publishing failed", workerFailure);
        }
    }

    private void runWorker(Worker worker) {
        long seen = 0;
        while (!closed) {
            long gate = cycleGate.get();
            if (gate == seen) {
                java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                continue;
            }
            seen = gate;
            try {
                drainTasks(worker);
            } catch (Throwable t) {
                workerFailure = t;
            } finally {
                pendingWorkers.decrementAndGet();
            }
        }
    }

    private void drainTasks(Worker worker) {
        int count = taskCount;
        int task;
        while ((task = nextTask.getAndIncrement()) < count) {
            worker.processInstrument(taskIds[task], taskBoundary[task]);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    /** One thread's scratch state; everything else it reads is frozen for the cycle. */
    private final class Worker {
        final Orders bids = new Orders();
        final Orders asks = new Orders();
        final BookView unscreened = new BookView(16);
        final Map<Key, BookView> classViews = new HashMap<>();
        final ArrayList<Key> keyPool = new ArrayList<>();
        final ArrayList<BookView> viewPool = new ArrayList<>();
        final long[] presentMask = new long[words];
        final CreditEngine.Overlay overlay = new CreditEngine.Overlay();
        final BookView individual = new BookView(16);
        int keysUsed;
        int viewsUsed;

        void processInstrument(int id, long boundary) {
            long start = System.nanoTime();
            collect(replica.book(id));
            long fingerprint = fingerprint();
            Instrument instrument = refData.instrument(id);
            List<Subscription> subs = byInstrument.get(id);
            boolean anyRefresh = false;
            boolean anyScreened = false;
            for (int k = 0; k < subs.size(); k++) {
                Subscription s = subs.get(k);
                anyRefresh |= s.refreshCycles > 0 && s.sinceSend + 1 >= s.refreshCycles;
                anyScreened |= s.screened;
            }
            if (!dirty[id] && !anyRefresh && !anyScreened && fingerprint == lastFingerprint[id]) {
                skipped.incrementAndGet(); // the book was touched but its displayed content is the same
                advanceRefresh(subs);
                cycles[id]++;
                return;
            }
            dirty[id] = false;
            lastFingerprint[id] = fingerprint;
            publishInstrument(instrument, subs, boundary);
            synchronized (cycleNanos) {
                cycleNanos.recordValue(System.nanoTime() - start);
            }
        }

        private void collect(OrderBook book) {
            bids.n = 0;
            asks.n = 0;
            book.forEachDisplayed(Side.BUY, bids::add);
            book.forEachDisplayed(Side.SELL, asks::add);
        }

        private long fingerprint() {
            long h = 0x9E3779B97F4A7C15L;
            h = mixIn(h, bids);
            h = mixIn(h * 31 + 1, asks);
            return h;
        }

        private void publishInstrument(Instrument instrument, List<Subscription> subs, long boundary) {
            Arrays.fill(presentMask, 0);
            mark(bids);
            mark(asks);
            classViews.clear();
            keysUsed = 0;
            viewsUsed = 0;
            int n = subs.size();
            int first = (int) (cycles[instrument.id()]++ % n);
            boolean unscreenedBuilt = false;
            for (int k = 0; k < n; k++) {
                Subscription s = subs.get((first + k) % n);
                BookView view;
                if (!s.screened) {
                    if (!unscreenedBuilt) {
                        build(unscreened, instrument, null, 0);
                        unscreenedBuilt = true;
                    }
                    view = unscreened;
                } else if (limitedAgainstPresent(limitedRows[instrument.id()][s.account])) {
                    build(individual, instrument, rows[instrument.id()][s.account], s.account);
                    view = individual;
                } else {
                    long[] row = rows[instrument.id()][s.account];
                    Key key = eligibilityKey(row);
                    view = classViews.get(key);
                    if (view == null) {
                        view = nextView(instrument.mdLevels());
                        build(view, instrument, row, 0);
                        classViews.put(key, view);
                        keysUsed++; // keep this key; otherwise it is reused for the next subscriber
                    }
                }
                s.current.copyFrom(view, s.depth);
                s.sinceSend++;
                if (!s.published || !s.current.sameAs(s.last) || s.refreshCycles > 0 && s.sinceSend >= s.refreshCycles) {
                    s.sink.publish(s.id, boundary, s.current);
                    s.last.copyFrom(s.current, s.depth);
                    s.published = true;
                    s.sinceSend = 0;
                    publishes.incrementAndGet();
                }
            }
        }

        private void mark(Orders orders) {
            for (int i = 0; i < orders.n; i++) {
                presentMask[orders.account[i] >>> 6] |= 1L << (orders.account[i] & 63);
            }
        }

        private boolean limitedAgainstPresent(long[] limitedRow) {
            for (int w = 0; w < words; w++) {
                if ((limitedRow[w] & presentMask[w]) != 0) {
                    return true;
                }
            }
            return false;
        }

        /** Screening class: the subscriber's eligibility restricted to the accounts present in the book. */
        private Key eligibilityKey(long[] row) {
            if (keysUsed == keyPool.size()) {
                keyPool.add(new Key(words));
            }
            Key k = keyPool.get(keysUsed);
            int h = 1;
            for (int w = 0; w < words; w++) {
                long v = row[w] & presentMask[w];
                k.bits[w] = v;
                h = 31 * h + Long.hashCode(v);
            }
            k.hash = h;
            return k;
        }

        private BookView nextView(int levels) {
            if (viewsUsed == viewPool.size()) {
                viewPool.add(new BookView(levels));
            }
            return viewPool.get(viewsUsed++);
        }

        /**
         * Builds a view. {@code eligible} null = unscreened, else an eligibility row; {@code account} above
         * 0 additionally clips each order to what that account could trade, with fills walked best first
         * so headroom is shared across a counterparty's orders exactly as an aggression would use it.
         */
        private void build(BookView view, Instrument instrument, long[] eligible, int account) {
            builds.incrementAndGet();
            view.clear(instrument.id());
            int levels = instrument.mdLevels();
            view.ensure(levels);
            long inc = instrument.mdPriceIncrement();
            for (int i = 0; i < bids.n; i++) {
                if (eligible != null && !visible(eligible, bids.account[i])) {
                    continue;
                }
                long qty = shown(account, bids.account[i], instrument, Side.SELL, bids.qty[i]);
                if (qty == 0) {
                    continue;
                }
                long bucket = Math.floorDiv(bids.price[i], inc) * inc;
                if (view.bidCount > 0 && view.bidPx[view.bidCount - 1] == bucket) {
                    view.bidQty[view.bidCount - 1] += qty;
                } else if (view.bidCount < levels) {
                    view.bidPx[view.bidCount] = bucket;
                    view.bidQty[view.bidCount++] = qty;
                } else {
                    break; // worse prices only from here
                }
            }
            overlay.clear();
            for (int i = 0; i < asks.n; i++) {
                if (eligible != null && !visible(eligible, asks.account[i])) {
                    continue;
                }
                long qty = shown(account, asks.account[i], instrument, Side.BUY, asks.qty[i]);
                if (qty == 0) {
                    continue;
                }
                long bucket = -Math.floorDiv(-asks.price[i], inc) * inc;
                if (view.askCount > 0 && view.askPx[view.askCount - 1] == bucket) {
                    view.askQty[view.askCount - 1] += qty;
                } else if (view.askCount < levels) {
                    view.askPx[view.askCount] = bucket;
                    view.askQty[view.askCount++] = qty;
                } else {
                    break;
                }
            }
            overlay.clear();
        }

        /** The quantity of a displayed order this account could take now, recording it against its headroom. */
        private long shown(int account, int counterparty, Instrument instrument, Side aggressorSide, long displayed) {
            if (account == 0) {
                return displayed;
            }
            CreditEngine credit = replica.credit();
            long cap = credit.capacity(account, counterparty, instrument.id(), aggressorSide, overlay);
            long qty = cap < displayed ? MatchingEngine.roundDownToExecutionSize(cap, instrument) : displayed;
            if (qty > 0) {
                credit.commitOverlay(overlay, account, counterparty, instrument.id(), aggressorSide, qty);
            }
            return qty;
        }
    }

    private static long mixIn(long h, Orders o) {
        for (int i = 0; i < o.n; i++) {
            h = (h ^ o.price[i]) * 0xff51afd7ed558ccdL;
            h = (h ^ o.qty[i]) * 0xc4ceb9fe1a85ec53L;
            h = (h ^ o.account[i]) * 0xff51afd7ed558ccdL;
            h ^= h >>> 29;
        }
        return h ^ o.n;
    }

    private static boolean visible(long[] eligible, int account) {
        return (eligible[account >>> 6] & (1L << (account & 63))) != 0;
    }
}
