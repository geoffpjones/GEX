package clob.engine;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.util.ArrayList;
import java.util.function.Predicate;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Single-threaded, deterministic matching engine for every book.
 *
 * <p>Determinism contract: the engine never reads a clock and never uses unseeded randomness.
 * Every input carries its sequenced time, which must not go backwards. Before an input at time
 * {@code t} is processed, every timer due at or before {@code t} fires, in (deadline, scheduling
 * order). A timer due exactly at {@code t} therefore fires before an input stamped {@code t}
 * (half-open windows, PLAN Q18 proposal).
 *
 * <p>Implemented so far (Phases 1–4): LIMIT, MARKET, ICEBERG, PEGGED ({@link PegManager}), MID
 * ({@link MidMatcher}) and FIXING ({@link FixingDesk}) orders; DAY, GTC, GTT, IOC and FOK; VISIBILITY
 * and PRICE priority; min qty/increment on every fill with residual cancellation; cancel,
 * cancel/replace and mass cancel with min TTL (reject or queue); bilateral credit with partial
 * fills clipped to headroom; taker and maker fairness races ({@link RaceManager}).
 *
 * <p>Credit in matching: the walk skips resting orders whose account is not bilaterally eligible
 * with the aggressor, or whose remaining credit cannot cover a minimum-size fill, and clips fills
 * to the largest valid size within credit. The book may therefore be crossed between accounts that
 * cannot trade. A credit change does not by itself trigger matching between resting orders (PLAN
 * Q31 default).
 */
public final class MatchingEngine {
    static final byte TIMER_GTT = 1;
    static final byte TIMER_TTL = 2;
    static final byte TIMER_PEG = 3;
    static final byte TIMER_FIXING = 4;
    static final byte TIMER_RACE = 5;

    private final RefData refData;
    final CreditEngine credit;
    final EngineListener listener;
    private final PegManager pegs;
    private final MidMatcher mid;
    private final FixingDesk fixing;
    private final RaceManager races;
    /** True while a race lottery applies its orders: they must not re-enter a race. */
    private boolean applyingRace;
    private final OrderBook[] books;
    private final Long2ObjectHashMap<Order> orders;
    private final OrderPool orderPool;
    private final LevelPool levelPool;
    private final TimerQueue timers;
    private final FillSimulator simulator;
    private final ArrayList<Order> scratch = new ArrayList<>();

    private long now = Long.MIN_VALUE;
    private long lastTradeId;
    private long lastPrioritySeq;

    public MatchingEngine(RefData refData, EngineConfig config, CreditEngine credit, EngineListener listener) {
        this.refData = refData;
        this.credit = credit;
        this.listener = listener;
        this.simulator = new FillSimulator(credit);
        this.books = new OrderBook[refData.instrumentCapacity()];
        for (Instrument instrument : refData.instruments()) {
            books[instrument.id()] = new OrderBook(instrument, config.initialLevelsPerSide());
        }
        this.orders = new Long2ObjectHashMap<>(Math.max(16, config.initialOrders() * 2), 0.65f);
        this.orderPool = new OrderPool(config.initialOrders(), config.maxLiveOrders());
        this.levelPool = new LevelPool(config.initialLevels());
        this.timers = new TimerQueue(config.initialTimers());
        this.pegs = new PegManager(this);
        this.mid = new MidMatcher(this);
        this.fixing = new FixingDesk(this, refData);
        this.races = new RaceManager(this, config.randomSeed());
    }

    // ------------------------------------------------------------------ inputs

    /** Advances logical time, firing due timers. */
    public void onTime(long time) {
        advanceTo(time);
    }

    public void onNewOrder(NewOrderCommand c, long time) {
        advanceTo(time);
        RejectReason reason = validate(c, time);
        if (reason != null) {
            listener.onOrderRejected(time, c.orderId, reason);
            return;
        }
        Order o = orderPool.acquire();
        if (o == null) {
            listener.onOrderRejected(time, c.orderId, RejectReason.CAPACITY_EXCEEDED);
            return;
        }
        o.orderId = c.orderId;
        o.instrumentId = c.instrumentId;
        o.accountId = c.accountId;
        o.userId = c.userId;
        o.side = c.side;
        o.type = c.type;
        o.tif = c.tif;
        o.price = c.type == OrderType.MARKET ? 0 : c.price;
        o.qty = c.qty;
        o.leaves = c.qty;
        o.maxShow = normaliseShow(c.maxShow);
        o.expireTime = c.tif == TimeInForce.GTT ? c.expireTime : 0;
        o.ttlStart = time;
        o.discretion = c.discretion;
        o.pegReference = c.pegReference;
        o.fixingEventId = c.fixingEventId;
        o.fresh = true;
        OrderBook book = books[o.instrumentId];
        orders.put(o.orderId, o);
        listener.onOrderAccepted(time, o.orderId);
        if (o.type == OrderType.PEGGED) {
            o.price = PegManager.pegPrice(o, book);
            pegs.link(book, o);
            pegs.ensureTick(book, time);
        }
        process(o, book, time);
        mid.check(book, time);
    }

    public void onCancel(long orderId, int userId, CancelSource source, long time) {
        advanceTo(time);
        Order o = orders.get(orderId);
        if (o == null) {
            listener.onRequestRejected(time, orderId, RequestType.CANCEL, RequestRejectReason.UNKNOWN_ORDER);
            return;
        }
        if (source == CancelSource.PARTICIPANT && !refData.isEntitled(userId, o.accountId)) {
            listener.onRequestRejected(time, orderId, RequestType.CANCEL, RequestRejectReason.NOT_ENTITLED);
            return;
        }
        OrderBook book = books[o.instrumentId];
        requestCancel(o, source, time);
        mid.check(book, time);
    }

    public void onReplace(ReplaceCommand c, long time) {
        advanceTo(time);
        Order o = orders.get(c.orderId);
        if (o == null) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.UNKNOWN_ORDER);
            return;
        }
        if (!refData.isEntitled(c.userId, o.accountId)) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.NOT_ENTITLED);
            return;
        }
        long maxShow = normaliseShow(c.maxShow);
        OrderBook book = books[o.instrumentId];
        RequestRejectReason reason = validateReplace(o, c.price, c.qty, maxShow);
        if (reason == null && o.pendingType == RequestType.CANCEL) {
            reason = RequestRejectReason.CANCEL_ALREADY_PENDING;
        }
        if (reason != null) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, reason);
            return;
        }
        Instrument instrument = book.instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        boolean raceHold = instrument.queueCancels() && RaceManager.takerRaceRunning(book);
        if (time < release && instrument.rejectOnTtl()) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.INSIDE_MIN_TTL);
            return;
        }
        if (time < release || raceHold) {
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED);
            } else if (time < release) {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.REPLACE;
            o.pendingRelease = release;
            o.pendingPrice = c.price;
            o.pendingQty = c.qty;
            o.pendingMaxShow = maxShow;
            o.pendingDiscretion = c.discretion;
            hold(o, book, raceHold);
            listener.onRequestQueued(time, o.orderId, RequestType.REPLACE, queuedUntil(o, book));
            return;
        }
        applyReplace(o, c.price, c.qty, maxShow, c.discretion, time);
        mid.check(book, time);
    }

    /**
     * Cancels every order in scope. Participant mass cancels obey min TTL order by order (PLAN Q14a,
     * provisional); disconnect and admin mass cancels bypass it. A participant request for a scope the
     * user is not entitled to is ignored.
     */
    public void onMassCancel(MassCancelScope scope, int scopeId, int requestingUserId, CancelSource source,
            long time) {
        advanceTo(time);
        if (source == CancelSource.PARTICIPANT) {
            boolean allowed = scope == MassCancelScope.USER
                    ? requestingUserId == scopeId
                    : refData.isEntitled(requestingUserId, scopeId);
            if (!allowed) {
                return;
            }
        }
        collect(scope == MassCancelScope.ACCOUNT ? o -> o.accountId == scopeId : o -> o.userId == scopeId);
        for (int i = 0; i < scratch.size(); i++) {
            requestCancel(scratch.get(i), source, time);
        }
        scratch.clear();
        checkAllMid(time);
    }

    // ------------------------------------------------------------------ credit inputs (sequenced)

    /** Creates or modifies a credit line (pool) extended by {@code giver}. */
    public void onCreditLine(int lineId, int giver, CreditType type, long limitUsd, long time) {
        advanceTo(time);
        credit.setLine(lineId, giver, type, limitUsd);
    }

    /** Maps the credit {@code from} extends to {@code to} (for one instrument, or {@link CreditEngine#ALL_INSTRUMENTS}). */
    public void onCreditRelationship(int from, int to, int instrumentId, int lineId, long time) {
        advanceTo(time);
        credit.setRelationship(from, to, instrumentId, lineId);
    }

    public void onUsdRate(String currency, long rateE8, long time) {
        advanceTo(time);
        credit.setUsdRate(currency, rateE8);
    }

    /** Current value dates (epoch days) for an instrument; {@code far} is used for SWAPs only. */
    public void onValueDates(int instrumentId, int near, int far, long time) {
        advanceTo(time);
        credit.setValueDates(instrumentId, near, far);
    }

    /** Releases settled credit usage for value dates before {@code today}. */
    public void onValueDateRoll(int today, long time) {
        advanceTo(time);
        credit.rollValueDate(today);
    }

    /** Cancels DAY orders. GTC orders roll with priority kept; GTT orders run to their expiry. */
    public void onEndOfDay(long time) {
        advanceTo(time);
        collect(o -> o.tif == TimeInForce.DAY);
        for (int i = 0; i < scratch.size(); i++) {
            cancelResting(scratch.get(i), CancelReason.END_OF_DAY, time);
        }
        scratch.clear();
        checkAllMid(time);
    }

    // ------------------------------------------------------------------ session (sequenced)

    /**
     * Changes the trading state of one instrument, or of all with {@code instrumentId} -1. On reopening,
     * races that expired meanwhile resolve and resting mid orders are re-checked.
     */
    public void onSessionState(int instrumentId, SessionState state, long time) {
        advanceTo(time);
        for (OrderBook book : books) {
            if (book == null || instrumentId != -1 && book.instrument.id() != instrumentId || book.session == state) {
                continue;
            }
            book.session = state;
            listener.onSessionState(time, book.instrument.id(), state);
            if (state == SessionState.OPEN) {
                races.resolveDeferred(book, time);
                book.lastMidBid = Long.MIN_VALUE; // force a mid re-check
                mid.check(book, time);
            }
        }
    }

    /** Deadline of the next internal timer, or {@code Long.MAX_VALUE}. The sequencer injects a time input then. */
    public long nextTimerDeadline() {
        return timers.isEmpty() ? Long.MAX_VALUE : timers.peekTime();
    }

    // ------------------------------------------------------------------ fixing inputs (sequenced)

    /**
     * Opens the next occurrence of a fixing event. Instants come from the event's time zone
     * ({@link clob.refdata.FixingEvent#occurrenceOn}). False if the event is unknown, already open,
     * still awaiting a rate, or the times are invalid.
     */
    public boolean onFixingSchedule(int fixingEventId, long cutoffTime, long fixingTime, long time) {
        advanceTo(time);
        return fixing.schedule(fixingEventId, cutoffTime, fixingTime, time);
    }

    /** The externally sourced fixing rate. Prices every pending match. False if none awaits a rate. */
    public boolean onFixingRate(int fixingEventId, int instrumentId, long rate, long time) {
        advanceTo(time);
        return fixing.rate(fixingEventId, instrumentId, rate, time);
    }

    // ------------------------------------------------------------------ diagnostics

    /** The book for an instrument, or null. Read-only view. */
    public OrderBook book(int instrumentId) {
        return instrumentId >= 0 && instrumentId < books.length ? books[instrumentId] : null;
    }

    /** Remaining quantity of a live order, or -1 if it is not live. */
    public long leavesOf(long orderId) {
        Order o = orders.get(orderId);
        return o == null ? -1 : o.leaves;
    }

    public int liveOrderCount() {
        return orders.size();
    }

    public CreditEngine credit() {
        return credit;
    }

    // ------------------------------------------------------------------ validation

    private RejectReason validate(NewOrderCommand c, long time) {
        Instrument instrument = refData.instrument(c.instrumentId);
        if (instrument == null || books[c.instrumentId] == null) {
            return RejectReason.UNKNOWN_INSTRUMENT;
        }
        if (c.side == null || c.type == null || c.tif == null) {
            return RejectReason.MISSING_FIELD;
        }
        SessionState session = books[c.instrumentId].session;
        if (session != SessionState.OPEN) {
            return session == SessionState.HALTED ? RejectReason.MARKET_HALTED : RejectReason.MARKET_NOT_OPEN;
        }
        if (!refData.isEntitled(c.userId, c.accountId)) {
            return RejectReason.NOT_ENTITLED;
        }
        if (orders.containsKey(c.orderId)) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        if (!instrument.supportedOrderTypes().contains(c.type)) {
            return RejectReason.UNSUPPORTED_ORDER_TYPE;
        }
        if (!instrument.supportedTifs().contains(c.tif)) {
            return RejectReason.UNSUPPORTED_TIF;
        }
        if (!instrument.isValidQty(c.qty)) {
            return RejectReason.INVALID_QUANTITY;
        }
        if ((c.type == OrderType.LIMIT || c.type == OrderType.ICEBERG) && !instrument.isValidPrice(c.price)) {
            return RejectReason.INVALID_PRICE;
        }
        if (c.type == OrderType.PEGGED) {
            if (c.pegReference == null) {
                return RejectReason.MISSING_FIELD;
            }
            if (PegManager.pegPrice(c.side, c.pegReference, c.discretion, books[c.instrumentId].referenceBid(),
                    books[c.instrumentId].referenceAsk(), instrument) == PegManager.NO_PRICE) {
                return RejectReason.PEG_REFERENCE_UNAVAILABLE;
            }
        }
        if (c.type == OrderType.MID && c.maxShow != NewOrderCommand.FULLY_VISIBLE) {
            return RejectReason.INVALID_SHOW;
        }
        if (c.type == OrderType.FIXING) {
            RejectReason r = fixing.validate(c, time);
            if (r != null) {
                return r;
            }
        }
        if (c.type == OrderType.ICEBERG && c.maxShow == NewOrderCommand.FULLY_VISIBLE) {
            return RejectReason.INVALID_SHOW;
        }
        if (c.maxShow != NewOrderCommand.FULLY_VISIBLE && !instrument.isValidShow(c.maxShow, c.qty)) {
            return RejectReason.INVALID_SHOW;
        }
        if (c.tif == TimeInForce.GTT && c.expireTime <= time) {
            return RejectReason.INVALID_EXPIRE_TIME;
        }
        return null;
    }

    private RequestRejectReason validateReplace(Order o, long price, long qty, long maxShow) {
        Instrument instrument = books[o.instrumentId].instrument;
        if (o.type == OrderType.FIXING || o.type == OrderType.MARKET) {
            return RequestRejectReason.NOT_REPLACEABLE;
        }
        if (!books[o.instrumentId].isOpen()) {
            return RequestRejectReason.MARKET_NOT_OPEN;
        }
        if (o.queue != null && o.type != OrderType.MID) {
            return RequestRejectReason.PENDING_IN_RACE; // waiting in a race ticket (PLAN Q37 default)
        }
        if ((o.type == OrderType.LIMIT || o.type == OrderType.ICEBERG) && !instrument.isValidPrice(price)) {
            return RequestRejectReason.INVALID_PRICE;
        }
        if (o.type == OrderType.MID && maxShow != Order.FULLY_VISIBLE) {
            return RequestRejectReason.INVALID_SHOW;
        }
        if (!instrument.isValidQty(qty)) {
            return RequestRejectReason.INVALID_QUANTITY;
        }
        if (qty <= o.cumQty) {
            return RequestRejectReason.QTY_NOT_ABOVE_FILLED;
        }
        if (qty - o.cumQty < instrument.minQty()) {
            return RequestRejectReason.LEAVES_BELOW_MIN_QTY;
        }
        if (o.type == OrderType.ICEBERG && maxShow == Order.FULLY_VISIBLE) {
            return RequestRejectReason.INVALID_SHOW;
        }
        if (maxShow != Order.FULLY_VISIBLE && !instrument.isValidShow(maxShow, qty)) {
            return RequestRejectReason.INVALID_SHOW;
        }
        return null;
    }

    private static long normaliseShow(long maxShow) {
        return maxShow == NewOrderCommand.FULLY_VISIBLE ? Order.FULLY_VISIBLE : maxShow;
    }

    /** Mid orders use mid TTL instead of min TTL (PLAN Q21 default). */
    private static long ttlFor(Order o, Instrument instrument) {
        return o.type == OrderType.MID ? instrument.midTtlNanos() : instrument.minTtlNanos();
    }

    // ------------------------------------------------------------------ order flow

    /** Routes a new or re-entering order to its book. */
    private void process(Order o, OrderBook book, long time) {
        switch (o.type) {
            case MID -> mid.process(o, book, time);
            case FIXING -> fixing.process(o, book, time);
            default -> processBook(o, book, time);
        }
    }

    /** Matches an order against the price ladder, then rests or terminates it according to type and TIF. */
    void processBook(Order o, OrderBook book, long time) {
        boolean fresh = o.fresh;
        o.fresh = false;
        if (!applyingRace && book.instrument.takerRaceNanos() > 0 && simulator.fillable(o, book) > 0) {
            races.joinTaker(o, book, time);
            return;
        }
        if (o.tif == TimeInForce.FOK && simulator.fillable(o, book) < o.leaves) {
            terminate(o, CancelReason.FOK_UNFILLED, time);
            return;
        }
        match(o, book, time);
        if (o.residualCancelQty > 0) {
            long qty = o.residualCancelQty;
            o.residualCancelQty = 0;
            listener.onOrderCancelled(time, o.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, qty);
            release(o, time);
            return;
        }
        if (o.leaves == 0) {
            release(o, time);
            return;
        }
        if (o.type == OrderType.MARKET) {
            // A market order never rests, whatever its TIF (PLAN Q7c default).
            terminate(o, CancelReason.MARKET_UNFILLED, time);
            return;
        }
        switch (o.tif) {
            case IOC -> terminate(o, CancelReason.IOC_UNFILLED, time);
            case FOK -> throw new IllegalStateException("FOK simulation disagreed with matching for order " + o.orderId);
            default -> {
                if (fresh && !applyingRace && book.instrument.makerRaceNanos() > 0 && races.tryJoinMaker(o, book, time)) {
                    return;
                }
                rest(o, book, time);
            }
        }
    }

    /** Applies an order drawn in a race lottery as an ordinary order. */
    void applyRaceOrder(Order o, OrderBook book, long time) {
        applyingRace = true;
        try {
            processBook(o, book, time);
        } finally {
            applyingRace = false;
        }
    }

    /** Records that an order's queued request also waits for the book's taker races (queue-cancels). */
    private void hold(Order o, OrderBook book, boolean raceHold) {
        if (!raceHold || o.heldByRace) {
            return;
        }
        o.heldByRace = true;
        if (book.heldCount == book.heldIds.length) {
            book.heldIds = java.util.Arrays.copyOf(book.heldIds, book.heldCount * 2);
        }
        book.heldIds[book.heldCount++] = o.orderId;
    }

    /** Best estimate of when a queued request will apply: TTL expiry or the end of the taker races. */
    private static long queuedUntil(Order o, OrderBook book) {
        return o.heldByRace ? Math.max(o.pendingRelease, RaceManager.latestTakerExpiry(book)) : o.pendingRelease;
    }

    /** Called when a book's last taker race resolves: applies held requests whose TTL has also passed. */
    void releaseRaceHolds(OrderBook book, long time) {
        int n = book.heldCount;
        book.heldCount = 0;
        for (int i = 0; i < n; i++) {
            Order o = orders.get(book.heldIds[i]);
            if (o == null || !o.heldByRace) {
                continue;
            }
            o.heldByRace = false;
            if (time >= o.pendingRelease && o.pendingType != null) {
                applyPending(o, time);
            } // otherwise its TTL timer applies it
        }
    }

    static boolean crosses(Order agg, long restingPrice) {
        if (agg.type == OrderType.MARKET) {
            return true;
        }
        return agg.side == Side.BUY ? restingPrice <= agg.price : restingPrice >= agg.price;
    }

    /**
     * VISIBILITY: hidden liquidity is locked until the aggressor has exhausted the visible top level.
     * The top level is screened per aggressor: the best crossing level where it actually traded
     * displayed quantity (levels whose displayed orders it cannot trade with are passed over,
     * PLAN Q1c default). Refreshed iceberg slices count as displayed. After that, and always under
     * PRICE: price, then displayed before hidden, then time.
     */
    private void match(Order agg, OrderBook book, long time) {
        BookSide opp = book.side(agg.side.opposite());
        if (book.instrument.matchPriority() == MatchPriority.VISIBILITY) {
            boolean unlocked = false;
            int i = 0;
            while (i < opp.size() && agg.leaves > 0) {
                PriceLevel level = opp.levelAt(i);
                if (!crosses(agg, level.price)) {
                    break;
                }
                int fills = walkVisible(agg, level, book, time);
                if (level.isEmpty()) {
                    opp.removeAt(i, levelPool);
                } else {
                    i++;
                }
                if (fills > 0) {
                    unlocked = true;
                    break;
                }
            }
            if (!unlocked || agg.leaves == 0) {
                return;
            }
        }
        int i = 0;
        while (i < opp.size() && agg.leaves > 0) {
            PriceLevel level = opp.levelAt(i);
            if (!crosses(agg, level.price)) {
                break;
            }
            walkVisible(agg, level, book, time);
            walkHidden(agg, level, book, time);
            if (level.isEmpty()) {
                opp.removeAt(i, levelPool);
            } else {
                i++;
            }
        }
    }

    /** Fills against the displayed queue in FIFO order, skipping orders it cannot trade with. Returns fills made. */
    private int walkVisible(Order agg, PriceLevel level, OrderBook book, long time) {
        int fills = 0;
        Order p = level.visHead;
        while (p != null && agg.leaves > 0) {
            Order next = p.next;
            if (tryFill(agg, p, level, book, time)) {
                fills++;
                // A refreshed iceberg moves to the tail; if it was last, it is also next.
                if (next == null && p.level == level && level.visTail == p) {
                    next = p;
                }
            }
            p = next;
        }
        return fills;
    }

    private void walkHidden(Order agg, PriceLevel level, OrderBook book, long time) {
        Order p = level.hidHead;
        while (p != null && agg.leaves > 0) {
            Order next = p.next;
            tryFill(agg, p, level, book, time);
            p = next;
        }
    }

    /** Fills as much as the queue, both orders and credit allow. False if this order must be skipped. */
    private boolean tryFill(Order agg, Order passive, PriceLevel level, OrderBook book, long time) {
        Instrument instrument = book.instrument;
        long qty = Math.min(agg.leaves, passive.inVisibleQueue ? passive.displayQty : passive.leaves);
        long cap = credit.capacity(agg.accountId, passive.accountId, instrument.id(), agg.side);
        if (cap < qty) {
            qty = roundDownToExecutionSize(cap, instrument);
            if (qty == 0) {
                return false;
            }
        }
        fill(agg, passive, level, book, time, qty);
        return true;
    }

    /** Largest valid execution size not above {@code qty}, or 0. */
    static long roundDownToExecutionSize(long qty, Instrument instrument) {
        long min = instrument.minQty();
        if (qty < min) {
            return 0;
        }
        return min + (qty - min) / instrument.qtyIncrement() * instrument.qtyIncrement();
    }

    private void fill(Order agg, Order passive, PriceLevel level, OrderBook book, long time, long qty) {
        long minQty = book.instrument.minQty();
        credit.commit(agg.accountId, passive.accountId, book.instrument.id(), agg.side, qty);
        agg.leaves -= qty;
        agg.cumQty += qty;
        passive.leaves -= qty;
        passive.cumQty += qty;
        if (passive.inVisibleQueue) {
            passive.displayQty -= qty;
            level.visibleQty -= qty;
            if (passive.type == OrderType.PEGGED) {
                level.pegVisibleQty -= qty;
            }
        } else {
            level.hiddenQty -= qty;
        }
        listener.onTrade(time, ++lastTradeId, book.instrument.id(), agg.orderId, passive.orderId, agg.side,
                level.price, book.instrument.pricePrecision(), qty, agg.leaves, passive.leaves);

        if (agg.leaves > 0 && agg.leaves < minQty) {
            agg.residualCancelQty = agg.leaves;
            agg.leaves = 0;
        }

        if (passive.leaves == 0) {
            level.remove(passive);
            release(passive, time);
        } else if (passive.leaves < minQty) {
            level.remove(passive);
            long residual = passive.leaves;
            passive.leaves = 0;
            listener.onOrderCancelled(time, passive.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, residual);
            release(passive, time);
        } else if (passive.inVisibleQueue && passive.displayQty < minQty) {
            // Slice exhausted (or below a fillable size): refresh to the back of the queue with new priority.
            level.remove(passive);
            passive.displayQty = Math.min(passive.maxShow, passive.leaves);
            passive.prioritySeq = ++lastPrioritySeq;
            level.addVisible(passive);
        }
    }

    private void rest(Order o, OrderBook book, long time) {
        PriceLevel level = book.side(o.side).getOrCreate(o.price, levelPool);
        o.prioritySeq = ++lastPrioritySeq;
        if (o.maxShow == 0) {
            o.displayQty = 0;
            level.addHidden(o);
        } else {
            o.displayQty = Math.min(o.maxShow, o.leaves);
            level.addVisible(o);
        }
        scheduleGtt(o);
    }

    void scheduleGtt(Order o) {
        if (o.tif == TimeInForce.GTT && !o.gttScheduled) {
            timers.schedule(o.expireTime, TIMER_GTT, o.orderId);
            o.gttScheduled = true;
        }
    }

    /** Takes a resting order off the price ladder (not a cancel). */
    void removeFromBook(Order o) {
        PriceLevel level = o.level;
        level.remove(o);
        books[o.instrumentId].side(o.side).removeIfEmpty(level, levelPool);
    }

    void schedule(long time, byte kind, long id) {
        timers.schedule(time, kind, id);
    }

    Order order(long orderId) {
        return orders.get(orderId);
    }

    long nextTradeId() {
        return ++lastTradeId;
    }

    long nextPrioritySeq() {
        return ++lastPrioritySeq;
    }

    private void checkAllMid(long time) {
        for (OrderBook book : books) {
            if (book != null) {
                mid.check(book, time);
            }
        }
    }

    private void requestCancel(Order o, CancelSource source, long time) {
        if (source != CancelSource.PARTICIPANT) {
            cancelResting(o, source.reason(), time); // any queued request is answered TOO_LATE
            return;
        }
        if (o.pendingType == RequestType.CANCEL) {
            listener.onRequestRejected(time, o.orderId, RequestType.CANCEL, RequestRejectReason.CANCEL_ALREADY_PENDING);
            return;
        }
        OrderBook book = books[o.instrumentId];
        Instrument instrument = book.instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        boolean raceHold = instrument.queueCancels() && RaceManager.takerRaceRunning(book);
        if (time < release && instrument.rejectOnTtl()) {
            // Inside TTL with reject-on-ttl: rejected even if a race would otherwise hold it (PLAN Q15 default).
            listener.onRequestRejected(time, o.orderId, RequestType.CANCEL, RequestRejectReason.INSIDE_MIN_TTL);
            return;
        }
        if (time < release || raceHold) {
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED);
            } else if (time < release) {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.CANCEL;
            o.pendingRelease = release;
            hold(o, book, raceHold);
            listener.onRequestQueued(time, o.orderId, RequestType.CANCEL, queuedUntil(o, book));
            return;
        }
        cancelResting(o, CancelReason.PARTICIPANT, time);
    }

    private void applyReplace(Order o, long price, long qty, long maxShow, long discretion, long time) {
        OrderBook book = books[o.instrumentId];
        long newLeaves = qty - o.cumQty;
        o.ttlStart = time; // an applied amend restarts min TTL (confirmed, PLAN Q14a)
        if (o.type == OrderType.MID) {
            if (qty <= o.qty && discretion == o.discretion) {
                o.qty = qty;
                o.leaves = newLeaves;
                listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves);
                return;
            }
            o.queue.remove(o);
            o.qty = qty;
            o.leaves = newLeaves;
            o.discretion = discretion;
            listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves);
            mid.process(o, book, time);
            return;
        }
        if (o.type == OrderType.PEGGED) {
            price = o.price; // a peg's price only moves on a peg tick
            o.discretion = discretion; // takes effect at the next tick, respecting the throttle
            if (o.suspended) {
                o.qty = qty;
                o.leaves = newLeaves;
                o.maxShow = maxShow;
                listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves);
                return;
            }
        }
        boolean keepPriority = price == o.price && qty <= o.qty && maxShow == o.maxShow;
        if (keepPriority) {
            o.level.adjust(o, newLeaves, o.inVisibleQueue ? Math.min(o.displayQty, newLeaves) : 0);
            o.qty = qty;
            listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves);
            return;
        }
        PriceLevel level = o.level;
        level.remove(o);
        book.side(o.side).removeIfEmpty(level, levelPool);
        o.price = price;
        o.qty = qty;
        o.leaves = newLeaves;
        o.maxShow = maxShow;
        listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves);
        process(o, book, time);
    }

    /** Cancels an order that is not resting (an aggressor that will not rest). */
    void terminate(Order o, CancelReason reason, long time) {
        long qty = o.leaves;
        o.leaves = 0;
        listener.onOrderCancelled(time, o.orderId, reason, qty);
        release(o, time);
    }

    void cancelResting(Order o, CancelReason reason, long time) {
        if (o.level != null) {
            removeFromBook(o);
        } else if (o.queue != null) {
            o.queue.remove(o);
        }
        terminate(o, reason, time);
    }

    /** Final step for every order: answer any queued request, unregister and return to the pool. */
    void release(Order o, long time) {
        if (o.pendingType != null) {
            listener.onRequestRejected(time, o.orderId, o.pendingType, RequestRejectReason.TOO_LATE);
        }
        if (o.inPegList) {
            pegs.unlink(books[o.instrumentId], o);
        }
        orders.remove(o.orderId);
        orderPool.release(o);
    }

    // ------------------------------------------------------------------ time

    private void advanceTo(long time) {
        if (time < now) {
            throw new IllegalArgumentException("Time went backwards: " + time + " < " + now);
        }
        now = time;
        while (!timers.isEmpty() && timers.peekTime() <= time) {
            long deadline = timers.peekTime();
            byte kind = timers.peekKind();
            long orderId = timers.peekId();
            timers.pop();
            fire(kind, orderId, deadline);
        }
    }

    private void fire(byte kind, long id, long deadline) {
        if (kind == TIMER_PEG) {
            OrderBook book = books[(int) id];
            pegs.onTick(book, deadline);
            mid.check(book, deadline);
            return;
        }
        if (kind == TIMER_FIXING) {
            fixing.onCutoff((int) id, deadline);
            return;
        }
        if (kind == TIMER_RACE) {
            races.onExpiry(id, deadline);
            checkAllMid(deadline);
            return;
        }
        Order o = orders.get(id);
        if (o == null) {
            return; // order already finished
        }
        OrderBook book = books[o.instrumentId];
        fireOrderTimer(kind, o, deadline);
        mid.check(book, deadline);
    }

    private void fireOrderTimer(byte kind, Order o, long deadline) {
        if (kind == TIMER_GTT) {
            if (o.expireTime == deadline && o.tif == TimeInForce.GTT) {
                cancelResting(o, CancelReason.EXPIRED, deadline);
            }
        } else if (kind == TIMER_TTL) {
            if (o.pendingType == null || o.pendingRelease != deadline || o.heldByRace) {
                return; // nothing queued, superseded, or still waiting for a taker race to finish
            }
            applyPending(o, deadline);
        }
    }

    /** Applies an order's queued cancel or replace. */
    private void applyPending(Order o, long deadline) {
        RequestType type = o.pendingType;
        o.pendingType = null;
        if (type == RequestType.CANCEL) {
            cancelResting(o, CancelReason.PARTICIPANT, deadline);
            return;
        }
        // Re-validate: the order may have filled while the replace was queued.
        RequestRejectReason reason = validateReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow);
        if (reason != null) {
            listener.onRequestRejected(deadline, o.orderId, RequestType.REPLACE, reason);
        } else {
            applyReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow, o.pendingDiscretion, deadline);
        }
    }

    // ------------------------------------------------------------------ iteration (not hot path)

    /** Collects resting orders matching {@code filter} into {@link #scratch}, in book priority order. */
    private void collect(Predicate<Order> filter) {
        scratch.clear();
        for (OrderBook book : books) {
            if (book == null) {
                continue;
            }
            collectSide(book.bids, filter);
            collectSide(book.asks, filter);
            collectQueue(book.midBuys, filter);
            collectQueue(book.midSells, filter);
            for (Order o = book.pegHead; o != null; o = o.pegNext) {
                if (o.suspended && filter.test(o)) {
                    scratch.add(o);
                }
            }
        }
        fixing.collect(filter, scratch);
        races.collect(filter, scratch);
    }

    private void collectQueue(OrderQueue queue, Predicate<Order> filter) {
        for (Order o = queue.head; o != null; o = o.next) {
            if (filter.test(o)) {
                scratch.add(o);
            }
        }
    }

    private void collectSide(BookSide side, Predicate<Order> filter) {
        for (int i = 0; i < side.size(); i++) {
            PriceLevel level = side.levelAt(i);
            for (Order o = level.visHead; o != null; o = o.next) {
                if (filter.test(o)) {
                    scratch.add(o);
                }
            }
            for (Order o = level.hidHead; o != null; o = o.next) {
                if (filter.test(o)) {
                    scratch.add(o);
                }
            }
        }
    }
}
