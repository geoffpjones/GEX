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
import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;

/**
 * Market data. Consumes the sequenced input stream (as a {@link clob.sequencer.Pipeline} follower) into a
 * <b>replica engine</b>: determinism makes its books and credit state identical to the matching engine's at
 * every sequence number, with no extra engine output. Each {@link InputCodec#MD_TICK} is a cycle boundary
 * at the same point in the stream for every subscriber (a coordinated cut).
 *
 * <p>At a boundary, for each instrument whose market data interval divides the boundary:
 * <ul>
 *   <li>collect displayed orders (hidden quantity never shown);
 *   <li>bucket by the market data price increment: bids round down, offers round up (confirmed), up to the
 *       instrument's levels;
 *   <li>unscreened: one view for all; screened: only orders from accounts the subscriber is bilaterally
 *       eligible to trade with (PLAN Q6e default: by relationship, not headroom). Subscribers with the same
 *       eligibility over the accounts present share one computation (equivalence classes);
 *   <li>publish only views that changed since that subscriber's last update, in a send order that rotates
 *       by one each cycle per instrument (the first subscriber in one cycle is last in the next).
 * </ul>
 *
 * Subscription changes may come from any thread; they apply at the next input.
 */
public final class MarketDataService implements InputHandler {
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
    private long skipped;
    /**
     * Per instrument: eligibility rows, rows[account] = bitset over accounts the account may trade with
     * (bilateral, overrides included). Rebuilt lazily when the credit version changes, so a subscriber's
     * screening key is a few word-wide ANDs instead of a credit lookup per account present.
     */
    private final long[][][] rows;
    private final long[] rowsVersion;
    private final int words;
    private long[] presentMask;
    private final Histogram cycleNanos = new Histogram(60_000_000_000L, 3);
    private long builds;
    private long publishes;

    // per-cycle scratch
    private final Orders bids = new Orders();
    private final Orders asks = new Orders();
    private final BookView unscreened = new BookView(16);
    private final Map<Key, BookView> classViews = new HashMap<>();

    static final class Subscription {
        final long id;
        final int account;
        final int instrumentId;
        final int depth;
        final boolean screened;
        final MarketDataSink sink;
        final BookView current = new BookView(16);
        final BookView last = new BookView(16);
        boolean published;

        Subscription(long id, int account, int instrumentId, int depth, boolean screened, MarketDataSink sink) {
            this.id = id;
            this.account = account;
            this.instrumentId = instrumentId;
            this.depth = depth;
            this.screened = screened;
            this.sink = sink;
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

    private final ArrayList<Key> keyPool = new ArrayList<>();
    private final ArrayList<BookView> viewPool = new ArrayList<>();
    private int keysUsed;
    private int viewsUsed;

    public MarketDataService(RefData refData, EngineConfig config, int maxAccounts) {
        this.refData = refData;
        this.replica = new MatchingEngine(refData, config, new CreditEngine(maxAccounts, refData),
                NullEngineListener.INSTANCE);
        this.dispatcher = new EngineDispatcher(replica, (s, t, ty, r) -> { });
        for (int i = 0; i < refData.instrumentCapacity(); i++) {
            byInstrument.add(new ArrayList<>());
        }
        cycles = new long[refData.instrumentCapacity()];
        lastBookVersion = new long[refData.instrumentCapacity()];
        lastCreditVersion = new long[refData.instrumentCapacity()];
        dirty = new boolean[refData.instrumentCapacity()];
        lastFingerprint = new long[refData.instrumentCapacity()];
        java.util.Arrays.fill(lastBookVersion, -1);
        rows = new long[refData.instrumentCapacity()][][];
        rowsVersion = new long[refData.instrumentCapacity()];
        java.util.Arrays.fill(rowsVersion, -1);
        words = (maxAccounts + 1 + 63) / 64;
        presentMask = new long[words];
        this.maxAccounts = maxAccounts;
    }

    private final int maxAccounts;

    private long[][] eligibilityRows(int instrumentId) {
        long version = replica.credit().version();
        if (rows[instrumentId] == null || rowsVersion[instrumentId] != version) {
            long[][] r = rows[instrumentId] == null ? new long[maxAccounts + 1][words] : rows[instrumentId];
            CreditEngine credit = replica.credit();
            for (int a = 1; a <= maxAccounts; a++) {
                java.util.Arrays.fill(r[a], 0);
                for (int b = 1; b <= maxAccounts; b++) {
                    if (credit.eligible(a, b, instrumentId)) {
                        r[a][b >>> 6] |= 1L << (b & 63);
                    }
                }
            }
            rows[instrumentId] = r;
            rowsVersion[instrumentId] = version;
        }
        return rows[instrumentId];
    }

    /** Instrument cycles skipped because neither the book nor credit changed. */
    public long skipped() {
        return skipped;
    }

    /**
     * Subscribes (any thread). {@code depth} 1 = top of book; 0 = all of the instrument's levels.
     * {@code screened} requires {@code account}.
     */
    public void subscribe(long id, int account, int instrumentId, int depth, boolean screened, MarketDataSink sink) {
        commands.add(() -> {
            Instrument instrument = refData.instrument(instrumentId);
            int levels = depth <= 0 ? instrument.mdLevels() : Math.min(depth, instrument.mdLevels());
            Subscription s = new Subscription(id, account, instrumentId, levels, screened, sink);
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

    /** Views computed so far (unscreened books plus one per screened equivalence class). */
    public long builds() {
        return builds;
    }

    public long publishes() {
        return publishes;
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
        for (int id = 0; id < byInstrument.size(); id++) {
            List<Subscription> subs = byInstrument.get(id);
            if (subs.isEmpty()) {
                continue;
            }
            Instrument instrument = refData.instrument(id);
            long interval = instrument.mdIntervalNanos();
            if (interval > 0 && Math.floorMod(boundary, interval) != 0) {
                continue;
            }
            long bookVersion = replica.book(id).version();
            long creditVersion = replica.credit().version();
            boolean creditSame = creditVersion == lastCreditVersion[id];
            if (!dirty[id] && bookVersion == lastBookVersion[id] && creditSame) {
                skipped++; // nothing anyone can see has changed (the rotation still advances)
                cycles[id]++;
                continue;
            }
            lastBookVersion[id] = bookVersion;
            long start = System.nanoTime();
            collect(replica.book(id));
            long fingerprint = fingerprint();
            if (!dirty[id] && creditSame && fingerprint == lastFingerprint[id]) {
                skipped++; // the book was touched but its displayed content is the same
                cycles[id]++;
                continue;
            }
            dirty[id] = false;
            lastFingerprint[id] = fingerprint;
            lastCreditVersion[id] = creditVersion;
            publishInstrument(instrument, subs, boundary);
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

    private static long mixIn(long h, Orders o) {
        for (int i = 0; i < o.n; i++) {
            h = (h ^ o.price[i]) * 0xff51afd7ed558ccdL;
            h = (h ^ o.qty[i]) * 0xc4ceb9fe1a85ec53L;
            h = (h ^ o.account[i]) * 0xff51afd7ed558ccdL;
            h ^= h >>> 29;
        }
        return h ^ o.n;
    }

    private void publishInstrument(Instrument instrument, List<Subscription> subs, long boundary) {
        java.util.Arrays.fill(presentMask, 0);
        mark(bids);
        mark(asks);
        classViews.clear();
        keysUsed = 0;
        viewsUsed = 0;
        int n = subs.size();
        int first = (int) (cycles[instrument.id()]++ % n);
        publishRotated(instrument, subs, boundary, n, first, false);
    }

    private void mark(Orders orders) {
        for (int i = 0; i < orders.n; i++) {
            presentMask[orders.account[i] >>> 6] |= 1L << (orders.account[i] & 63);
        }
    }

    private void publishRotated(Instrument instrument, List<Subscription> subs, long boundary, int n, int first,
            boolean unscreenedBuilt) {
        for (int k = 0; k < n; k++) {
            Subscription s = subs.get((first + k) % n);
            BookView view;
            if (!s.screened) {
                if (!unscreenedBuilt) {
                    build(unscreened, instrument, null);
                    unscreenedBuilt = true;
                }
                view = unscreened;
            } else {
                long[] row = eligibilityRows(instrument.id())[s.account];
                Key key = eligibilityKey(row);
                view = classViews.get(key);
                if (view == null) {
                    view = nextView(instrument.mdLevels());
                    build(view, instrument, row);
                    classViews.put(key, view);
                    keysUsed++; // keep this key; otherwise it is reused for the next subscriber
                }
            }
            s.current.copyFrom(view, s.depth);
            if (!s.published || !s.current.sameAs(s.last)) {
                s.sink.publish(s.id, boundary, s.current);
                s.last.copyFrom(s.current, s.depth);
                s.published = true;
                publishes++;
            }
        }
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

    private static boolean visible(long[] eligible, int account) {
        return (eligible[account >>> 6] & (1L << (account & 63))) != 0;
    }

    /** Builds a view; {@code eligible} null = unscreened, else an eligibility row (bitset over accounts). */
    private void build(BookView view, Instrument instrument, long[] eligible) {
        builds++;
        view.clear(instrument.id());
        int levels = instrument.mdLevels();
        view.ensure(levels);
        long inc = instrument.mdPriceIncrement();
        for (int i = 0; i < bids.n; i++) {
            if (eligible != null && !visible(eligible, bids.account[i])) {
                continue;
            }
            long bucket = Math.floorDiv(bids.price[i], inc) * inc;
            if (view.bidCount > 0 && view.bidPx[view.bidCount - 1] == bucket) {
                view.bidQty[view.bidCount - 1] += bids.qty[i];
            } else if (view.bidCount < levels) {
                view.bidPx[view.bidCount] = bucket;
                view.bidQty[view.bidCount++] = bids.qty[i];
            } else {
                break; // worse prices only from here
            }
        }
        for (int i = 0; i < asks.n; i++) {
            if (eligible != null && !visible(eligible, asks.account[i])) {
                continue;
            }
            long bucket = -Math.floorDiv(-asks.price[i], inc) * inc;
            if (view.askCount > 0 && view.askPx[view.askCount - 1] == bucket) {
                view.askQty[view.askCount - 1] += asks.qty[i];
            } else if (view.askCount < levels) {
                view.askPx[view.askCount] = bucket;
                view.askQty[view.askCount++] = asks.qty[i];
            } else {
                break;
            }
        }
    }
}
