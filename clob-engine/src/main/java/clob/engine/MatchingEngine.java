package clob.engine;

import clob.common.Side;
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
 * <p>Implemented so far (Phases 1–2): LIMIT, MARKET and ICEBERG orders; DAY, GTC, GTT, IOC and FOK;
 * VISIBILITY and PRICE priority; min qty/increment on every fill with residual cancellation;
 * cancel, cancel/replace and mass cancel with min TTL (reject or queue). Credit, pegs, mid,
 * fixing and races come in later phases.
 */
public final class MatchingEngine {
    private static final byte TIMER_GTT = 1;
    private static final byte TIMER_TTL = 2;

    private final RefData refData;
    private final EngineListener listener;
    private final OrderBook[] books;
    private final Long2ObjectHashMap<Order> orders;
    private final OrderPool orderPool;
    private final LevelPool levelPool;
    private final TimerQueue timers;
    private final FillSimulator simulator = new FillSimulator();
    private final ArrayList<Order> scratch = new ArrayList<>();

    private long now = Long.MIN_VALUE;
    private long lastTradeId;
    private long lastPrioritySeq;

    public MatchingEngine(RefData refData, EngineConfig config, EngineListener listener) {
        this.refData = refData;
        this.listener = listener;
        this.books = new OrderBook[refData.instrumentCapacity()];
        for (Instrument instrument : refData.instruments()) {
            books[instrument.id()] = new OrderBook(instrument, config.initialLevelsPerSide());
        }
        this.orders = new Long2ObjectHashMap<>(Math.max(16, config.initialOrders() * 2), 0.65f);
        this.orderPool = new OrderPool(config.initialOrders(), config.maxLiveOrders());
        this.levelPool = new LevelPool(config.initialLevels());
        this.timers = new TimerQueue(config.initialTimers());
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
        orders.put(o.orderId, o);
        listener.onOrderAccepted(time, o.orderId);
        process(o, books[o.instrumentId], time);
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
        requestCancel(o, source, time);
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
        RequestRejectReason reason = validateReplace(o, c.price, c.qty, maxShow);
        if (reason == null && o.pendingType == RequestType.CANCEL) {
            reason = RequestRejectReason.CANCEL_ALREADY_PENDING;
        }
        if (reason != null) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, reason);
            return;
        }
        Instrument instrument = books[o.instrumentId].instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        if (time < release) {
            if (instrument.rejectOnTtl()) {
                listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.INSIDE_MIN_TTL);
                return;
            }
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED);
            } else {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.REPLACE;
            o.pendingRelease = release;
            o.pendingPrice = c.price;
            o.pendingQty = c.qty;
            o.pendingMaxShow = maxShow;
            listener.onRequestQueued(time, o.orderId, RequestType.REPLACE, release);
            return;
        }
        applyReplace(o, c.price, c.qty, maxShow, time);
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
    }

    /** Cancels DAY orders. GTC orders roll with priority kept; GTT orders run to their expiry. */
    public void onEndOfDay(long time) {
        advanceTo(time);
        collect(o -> o.tif == TimeInForce.DAY);
        for (int i = 0; i < scratch.size(); i++) {
            cancelResting(scratch.get(i), CancelReason.END_OF_DAY, time);
        }
        scratch.clear();
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

    // ------------------------------------------------------------------ validation

    private RejectReason validate(NewOrderCommand c, long time) {
        Instrument instrument = refData.instrument(c.instrumentId);
        if (instrument == null || books[c.instrumentId] == null) {
            return RejectReason.UNKNOWN_INSTRUMENT;
        }
        if (c.side == null || c.type == null || c.tif == null) {
            return RejectReason.MISSING_FIELD;
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
        if (c.type != OrderType.LIMIT && c.type != OrderType.MARKET && c.type != OrderType.ICEBERG) {
            return RejectReason.ORDER_TYPE_NOT_IMPLEMENTED;
        }
        if (!instrument.isValidQty(c.qty)) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (c.type != OrderType.MARKET && !instrument.isValidPrice(c.price)) {
            return RejectReason.INVALID_PRICE;
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
        if (!instrument.isValidPrice(price)) {
            return RequestRejectReason.INVALID_PRICE;
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

    private static long ttlFor(Order o, Instrument instrument) {
        return instrument.minTtlNanos();
    }

    // ------------------------------------------------------------------ order flow

    /** Matches an incoming (or re-entering) order, then rests or terminates it according to type and TIF. */
    private void process(Order o, OrderBook book, long time) {
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
            default -> rest(o, book, time);
        }
    }

    static boolean crosses(Order agg, long restingPrice) {
        if (agg.type == OrderType.MARKET) {
            return true;
        }
        return agg.side == Side.BUY ? restingPrice <= agg.price : restingPrice >= agg.price;
    }

    /**
     * VISIBILITY: first fill only displayed liquidity at the best visible level. Hidden liquidity
     * unlocks once that level's displayed quantity is exhausted (refreshed iceberg slices count as
     * displayed, PLAN Q1c default). After that, and always under PRICE: price, then displayed before
     * hidden, then time.
     */
    private void match(Order agg, OrderBook book, long time) {
        BookSide opp = book.side(agg.side.opposite());
        if (book.instrument.matchPriority() == MatchPriority.VISIBILITY) {
            int top = opp.firstVisibleIndex();
            if (top < 0) {
                return;
            }
            PriceLevel level = opp.levelAt(top);
            if (!crosses(agg, level.price)) {
                return;
            }
            while (agg.leaves > 0 && level.visHead != null) {
                fill(agg, level.visHead, level, book, time);
            }
            if (level.isEmpty()) {
                opp.removeAt(top, levelPool);
            }
            if (agg.leaves == 0) {
                return;
            }
        }
        int i = 0;
        while (i < opp.size() && agg.leaves > 0) {
            PriceLevel level = opp.levelAt(i);
            if (!crosses(agg, level.price)) {
                break;
            }
            while (agg.leaves > 0 && level.visHead != null) {
                fill(agg, level.visHead, level, book, time);
            }
            while (agg.leaves > 0 && level.hidHead != null) {
                fill(agg, level.hidHead, level, book, time);
            }
            if (level.isEmpty()) {
                opp.removeAt(i, levelPool);
            } else {
                i++;
            }
        }
    }

    private void fill(Order agg, Order passive, PriceLevel level, OrderBook book, long time) {
        long minQty = book.instrument.minQty();
        long available = passive.inVisibleQueue ? passive.displayQty : passive.leaves;
        long qty = Math.min(agg.leaves, available);
        agg.leaves -= qty;
        agg.cumQty += qty;
        passive.leaves -= qty;
        passive.cumQty += qty;
        if (passive.inVisibleQueue) {
            passive.displayQty -= qty;
            level.visibleQty -= qty;
        } else {
            level.hiddenQty -= qty;
        }
        listener.onTrade(time, ++lastTradeId, book.instrument.id(), agg.orderId, passive.orderId, agg.side,
                level.price, qty, agg.leaves, passive.leaves);

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
        if (o.tif == TimeInForce.GTT && !o.gttScheduled) {
            timers.schedule(o.expireTime, TIMER_GTT, o.orderId);
            o.gttScheduled = true;
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
        Instrument instrument = books[o.instrumentId].instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        if (time < release) {
            if (instrument.rejectOnTtl()) {
                listener.onRequestRejected(time, o.orderId, RequestType.CANCEL, RequestRejectReason.INSIDE_MIN_TTL);
                return;
            }
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED);
            } else {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.CANCEL;
            o.pendingRelease = release;
            listener.onRequestQueued(time, o.orderId, RequestType.CANCEL, release);
            return;
        }
        cancelResting(o, CancelReason.PARTICIPANT, time);
    }

    private void applyReplace(Order o, long price, long qty, long maxShow, long time) {
        OrderBook book = books[o.instrumentId];
        long newLeaves = qty - o.cumQty;
        o.ttlStart = time; // an applied amend restarts min TTL (confirmed, PLAN Q14a)
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
    private void terminate(Order o, CancelReason reason, long time) {
        long qty = o.leaves;
        o.leaves = 0;
        listener.onOrderCancelled(time, o.orderId, reason, qty);
        release(o, time);
    }

    private void cancelResting(Order o, CancelReason reason, long time) {
        PriceLevel level = o.level;
        if (level != null) {
            level.remove(o);
            books[o.instrumentId].side(o.side).removeIfEmpty(level, levelPool);
        }
        terminate(o, reason, time);
    }

    /** Final step for every order: answer any queued request, unregister and return to the pool. */
    private void release(Order o, long time) {
        if (o.pendingType != null) {
            listener.onRequestRejected(time, o.orderId, o.pendingType, RequestRejectReason.TOO_LATE);
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

    private void fire(byte kind, long orderId, long deadline) {
        Order o = orders.get(orderId);
        if (o == null) {
            return; // order already finished
        }
        if (kind == TIMER_GTT) {
            if (o.expireTime == deadline && o.tif == TimeInForce.GTT) {
                cancelResting(o, CancelReason.EXPIRED, deadline);
            }
        } else if (kind == TIMER_TTL) {
            if (o.pendingType == null || o.pendingRelease != deadline) {
                return;
            }
            RequestType type = o.pendingType;
            o.pendingType = null;
            if (type == RequestType.CANCEL) {
                cancelResting(o, CancelReason.PARTICIPANT, deadline);
            } else {
                // Re-validate: the order may have filled while the replace was queued.
                RequestRejectReason reason = validateReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow);
                if (reason != null) {
                    listener.onRequestRejected(deadline, o.orderId, RequestType.REPLACE, reason);
                } else {
                    applyReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow, deadline);
                }
            }
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
