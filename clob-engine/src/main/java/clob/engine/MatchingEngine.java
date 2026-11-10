package clob.engine;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditRefusal;
import clob.credit.CreditType;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
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
 * <p>VISIBILITY, resting orders: a resting order newly displaying quantity trades at once with any
 * hidden order that crosses it, at the hidden order's price. Fully hidden orders trigger nothing, and
 * an iceberg shows only its tip. See {@link #sweepHidden}.
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
    /** Orders that newly displayed quantity while hidden liquidity crosses them; swept once the current order is done. */
    private long[] newlyDisplayed = new long[256];
    private int newlyDisplayedCount;

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
            books[instrument.id()].dataHalted = !credit.hasRequiredData(instrument.id());
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
        book.version++;
        orders.put(o.orderId, o);
        listener.onOrderAccepted(time, o.orderId);
        if (o.type == OrderType.PEGGED) {
            o.price = PegManager.pegPrice(o, book);
            pegs.link(book, o);
            pegs.ensureTick(book, time);
        }
        if (book.isPreOpen()) {
            o.fresh = false;
            races.joinPreOpen(o, book, time);
        } else {
            process(o, book, time);
            sweepNewlyDisplayed(time);
        }
        mid.check(book, time);
    }

    public void onCancel(long orderId, int userId, CancelSource source, long time) {
        onCancel(orderId, userId, source, 0, time);
    }

    public void onCancel(long orderId, int userId, CancelSource source, long requestId, long time) {
        advanceTo(time);
        Order o = orders.get(orderId);
        if (o == null) {
            listener.onRequestRejected(time, orderId, RequestType.CANCEL, RequestRejectReason.UNKNOWN_ORDER, requestId);
            return;
        }
        if (source == CancelSource.PARTICIPANT && !refData.isEntitled(userId, o.accountId)) {
            listener.onRequestRejected(time, orderId, RequestType.CANCEL, RequestRejectReason.NOT_ENTITLED, requestId);
            return;
        }
        OrderBook book = books[o.instrumentId];
        book.version++;
        requestCancel(o, source, requestId, time);
        mid.check(book, time);
    }

    public void onReplace(ReplaceCommand c, long time) {
        advanceTo(time);
        Order o = orders.get(c.orderId);
        if (o == null) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.UNKNOWN_ORDER, c.requestId);
            return;
        }
        if (!refData.isEntitled(c.userId, o.accountId)) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.NOT_ENTITLED, c.requestId);
            return;
        }
        long maxShow = normaliseShow(c.maxShow);
        OrderBook book = books[o.instrumentId];
        book.version++;
        RequestRejectReason reason = validateReplace(o, c.price, c.qty, maxShow);
        if (reason == null && o.pendingType == RequestType.CANCEL) {
            reason = RequestRejectReason.CANCEL_ALREADY_PENDING;
        }
        if (reason != null) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, reason, c.requestId);
            return;
        }
        if (book.isPreOpen()) {
            applyReplace(o, c.price, c.qty, maxShow, c.discretion, c.requestId, time); // no TTL before the open
            return;
        }
        Instrument instrument = book.instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        RaceManager.Race holding = holdingRace(o, book);
        boolean raceHold = holding != null;
        if (time < release && instrument.rejectOnTtl()) {
            listener.onRequestRejected(time, c.orderId, RequestType.REPLACE, RequestRejectReason.INSIDE_MIN_TTL, c.requestId);
            return;
        }
        if (time < release || raceHold) {
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED,
                        o.pendingRequestId);
            } else if (time < release) {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.REPLACE;
            o.pendingRequestId = c.requestId;
            o.pendingRelease = release;
            o.pendingPrice = c.price;
            o.pendingQty = c.qty;
            o.pendingMaxShow = maxShow;
            o.pendingDiscretion = c.discretion;
            hold(o, book, holding);
            listener.onRequestQueued(time, o.orderId, RequestType.REPLACE, queuedUntil(o), c.requestId);
            return;
        }
        applyReplace(o, c.price, c.qty, maxShow, c.discretion, c.requestId, time);
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
            requestCancel(scratch.get(i), source, 0, time);
        }
        scratch.clear();
        checkAllMid(time);
    }

    // ------------------------------------------------------------------ credit inputs (sequenced)

    /** Creates or modifies a credit line (pool) extended by {@code giver}. Null if applied. */
    public CreditRefusal onCreditLine(int lineId, int giver, CreditType type, long limitUsd, long time) {
        advanceTo(time);
        return credit.setLine(lineId, giver, type, limitUsd);
    }

    /** Maps the credit {@code from} extends to {@code to} (for one instrument, or {@link CreditEngine#ALL_INSTRUMENTS}). */
    public CreditRefusal onCreditRelationship(int from, int to, int instrumentId, int lineId, long time) {
        advanceTo(time);
        return credit.setRelationship(from, to, instrumentId, lineId);
    }

    public CreditRefusal onUsdRate(String currency, long rateE8, long time) {
        advanceTo(time);
        CreditRefusal refusal = credit.setUsdRate(currency, rateE8);
        refreshDataHalts(time);
        return refusal;
    }

    /** Current value dates (epoch days) for an instrument; {@code far} is used for SWAPs only. */
    public CreditRefusal onValueDates(int instrumentId, int near, int far, long time) {
        advanceTo(time);
        CreditRefusal refusal = credit.setValueDates(instrumentId, near, far);
        refreshDataHalts(time);
        return refusal;
    }

    /** Sets the business date and releases settled credit usage for value dates before {@code today}. */
    public CreditRefusal onValueDateRoll(int today, long time) {
        advanceTo(time);
        CreditRefusal refusal = credit.rollValueDate(today, (lineId, usageE8, limitE8) ->
                listener.onCreditLimitBreached(time, lineId, usageE8, limitE8));
        refreshDataHalts(time);
        return refusal;
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
        timers.compact(this::timerLive);
        listener.onEndOfDay(time);
    }

    /** False for a timer that can no longer do anything (its order has gone). */
    private boolean timerLive(byte kind, long id) {
        return kind != TIMER_GTT && kind != TIMER_TTL || orders.containsKey(id);
    }

    // ------------------------------------------------------------------ session (sequenced)

    /**
     * Changes the operator-set trading state of one instrument, or of all with {@code instrumentId} -1.
     * Leaving OPEN cancels orders waiting in races; moving from PRE_OPEN to OPEN draws the pre-open lottery.
     */
    public void onSessionState(int instrumentId, SessionState state, long time) {
        advanceTo(time);
        for (OrderBook book : books) {
            if (book == null || instrumentId != -1 && book.instrument.id() != instrumentId || book.session == state) {
                continue;
            }
            SessionState before = book.session();
            book.session = state;
            onEffectiveStateChange(book, before, time);
        }
    }

    private void onEffectiveStateChange(OrderBook book, SessionState before, long time) {
        SessionState after = book.session();
        if (before == after) {
            return;
        }
        book.version++;
        listener.onSessionState(time, book.instrument.id(), after);
        if (before == SessionState.PRE_OPEN && after == SessionState.OPEN) {
            races.resolvePreOpen(book, time);
        } else if (before == SessionState.OPEN || before == SessionState.PRE_OPEN) {
            races.abortAll(book, time);
        }
        if (after == SessionState.OPEN) {
            book.lastMidBid = Long.MIN_VALUE; // force a mid re-check
            mid.check(book, time);
        }
    }

    /** Halts instruments whose USD rate or value date is missing, and resumes those whose data has arrived. */
    private void refreshDataHalts(long time) {
        for (OrderBook book : books) {
            if (book != null) {
                boolean missing = !credit.hasRequiredData(book.instrument.id());
                if (missing != book.dataHalted) {
                    SessionState before = book.session();
                    book.dataHalted = missing;
                    onEffectiveStateChange(book, before, time);
                }
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

    // ------------------------------------------------------------------ snapshots

    private static final int END_OF_ORDERS = 0;
    private static final int AT_VISIBLE = 1;
    private static final int AT_HIDDEN = 2;
    private static final int AT_MID = 3;
    private static final int AT_NOWHERE = 4; // a suspended peg
    private static final int AT_FIXING = 5;
    private static final int AT_RACE = 6;

    /**
     * Writes the complete engine state, so that an engine restored from it behaves identically to this
     * one from here on. Runs on an offline replica, never on the live engine.
     */
    public void save(DataOutput out) throws IOException {
        out.writeLong(now);
        out.writeLong(lastTradeId);
        out.writeLong(lastPrioritySeq);
        credit.save(out);
        timers.save(out);
        for (OrderBook book : books) {
            if (book == null) {
                continue;
            }
            out.writeUTF(book.session.name());
            out.writeBoolean(book.dataHalted);
            out.writeBoolean(book.pegTickScheduled);
            out.writeLong(book.lastMidBid);
            out.writeLong(book.lastMidAsk);
            out.writeLong(book.version);
            out.writeInt(book.heldCount);
            for (int i = 0; i < book.heldCount; i++) {
                out.writeLong(book.heldIds[i]);
            }
        }
        races.saveMeta(out);
        fixing.saveMeta(out);
        for (OrderBook book : books) {
            if (book == null) {
                continue;
            }
            saveSide(book.bids, out);
            saveSide(book.asks, out);
            saveQueue(book.midBuys, out);
            saveQueue(book.midSells, out);
            for (Order o = book.pegHead; o != null; o = o.pegNext) {
                if (o.suspended) {
                    out.writeByte(AT_NOWHERE);
                    saveOrder(o, out);
                }
            }
        }
        fixing.forEachResting((o, instrument, event) -> {
            out.writeByte(AT_FIXING);
            out.writeInt(instrument);
            out.writeInt(event);
            saveOrder(o, out);
        });
        races.forEachWaiting((o, raceId, account) -> {
            out.writeByte(AT_RACE);
            out.writeLong(raceId);
            out.writeInt(account);
            saveOrder(o, out);
        });
        out.writeByte(END_OF_ORDERS);
        // Peg lists, in arrival order (the orders themselves were written above).
        for (OrderBook book : books) {
            if (book != null) {
                out.writeInt(book.pegCount);
                for (Order o = book.pegHead; o != null; o = o.pegNext) {
                    out.writeLong(o.orderId);
                }
            }
        }
    }

    private void saveSide(BookSide side, DataOutput out) throws IOException {
        for (int i = 0; i < side.size(); i++) {
            PriceLevel level = side.levelAt(i);
            for (Order o = level.visHead; o != null; o = o.next) {
                out.writeByte(AT_VISIBLE);
                saveOrder(o, out);
            }
            for (Order o = level.hidHead; o != null; o = o.next) {
                out.writeByte(AT_HIDDEN);
                saveOrder(o, out);
            }
        }
    }

    private void saveQueue(OrderQueue queue, DataOutput out) throws IOException {
        for (Order o = queue.head; o != null; o = o.next) {
            out.writeByte(AT_MID);
            saveOrder(o, out);
        }
    }

    private static void writeEnum(DataOutput out, Enum<?> e) throws IOException {
        out.writeUTF(e == null ? "" : e.name());
    }

    private static void saveOrder(Order o, DataOutput out) throws IOException {
        out.writeLong(o.orderId);
        out.writeInt(o.instrumentId);
        out.writeInt(o.accountId);
        out.writeInt(o.userId);
        writeEnum(out, o.side);
        writeEnum(out, o.type);
        writeEnum(out, o.tif);
        out.writeLong(o.price);
        out.writeLong(o.qty);
        out.writeLong(o.cumQty);
        out.writeLong(o.leaves);
        out.writeLong(o.maxShow);
        out.writeLong(o.displayQty);
        out.writeLong(o.expireTime);
        out.writeBoolean(o.gttScheduled);
        out.writeLong(o.ttlStart);
        out.writeLong(o.prioritySeq);
        writeEnum(out, o.pendingType);
        out.writeLong(o.pendingRelease);
        out.writeLong(o.pendingPrice);
        out.writeLong(o.pendingQty);
        out.writeLong(o.pendingMaxShow);
        out.writeLong(o.pendingDiscretion);
        out.writeLong(o.pendingRequestId);
        out.writeLong(o.cancelRequestId);
        out.writeLong(o.discretion);
        writeEnum(out, o.pegReference);
        out.writeBoolean(o.suspended);
        out.writeInt(o.fixingEventId);
        out.writeBoolean(o.heldByRace);
        out.writeLong(o.holdUntilRace);
        out.writeLong(o.holdRaceExpiry);
        out.writeBoolean(o.inTakerRace);
    }

    private static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException {
        String name = in.readUTF();
        return name.isEmpty() ? null : Enum.valueOf(type, name);
    }

    private Order restoreOrder(DataInput in) throws IOException {
        Order o = orderPool.acquire();
        if (o == null) {
            throw new IllegalStateException("Snapshot holds more live orders than maxLiveOrders");
        }
        o.orderId = in.readLong();
        o.instrumentId = in.readInt();
        o.accountId = in.readInt();
        o.userId = in.readInt();
        o.side = readEnum(in, Side.class);
        o.type = readEnum(in, OrderType.class);
        o.tif = readEnum(in, TimeInForce.class);
        o.price = in.readLong();
        o.qty = in.readLong();
        o.cumQty = in.readLong();
        o.leaves = in.readLong();
        o.maxShow = in.readLong();
        o.displayQty = in.readLong();
        o.expireTime = in.readLong();
        o.gttScheduled = in.readBoolean();
        o.ttlStart = in.readLong();
        o.prioritySeq = in.readLong();
        o.pendingType = readEnum(in, RequestType.class);
        o.pendingRelease = in.readLong();
        o.pendingPrice = in.readLong();
        o.pendingQty = in.readLong();
        o.pendingMaxShow = in.readLong();
        o.pendingDiscretion = in.readLong();
        o.pendingRequestId = in.readLong();
        o.cancelRequestId = in.readLong();
        o.discretion = in.readLong();
        o.pegReference = readEnum(in, PegReference.class);
        o.suspended = in.readBoolean();
        o.fixingEventId = in.readInt();
        o.heldByRace = in.readBoolean();
        o.holdUntilRace = in.readLong();
        o.holdRaceExpiry = in.readLong();
        o.inTakerRace = in.readBoolean();
        orders.put(o.orderId, o);
        return o;
    }

    /** Loads a saved state into this new, unused engine. */
    public void restore(DataInput in) throws IOException {
        now = in.readLong();
        lastTradeId = in.readLong();
        lastPrioritySeq = in.readLong();
        credit.restore(in);
        timers.restore(in);
        for (OrderBook book : books) {
            if (book == null) {
                continue;
            }
            book.session = SessionState.valueOf(in.readUTF());
            book.dataHalted = in.readBoolean();
            book.pegTickScheduled = in.readBoolean();
            book.lastMidBid = in.readLong();
            book.lastMidAsk = in.readLong();
            book.version = in.readLong();
            book.heldCount = in.readInt();
            if (book.heldCount > book.heldIds.length) {
                book.heldIds = new long[book.heldCount * 2];
            }
            for (int i = 0; i < book.heldCount; i++) {
                book.heldIds[i] = in.readLong();
            }
        }
        races.restoreMeta(in);
        fixing.restoreMeta(in);
        while (true) {
            int at = in.readByte();
            if (at == END_OF_ORDERS) {
                break;
            }
            switch (at) {
                case AT_VISIBLE -> {
                    Order o = restoreOrder(in);
                    books[o.instrumentId].side(o.side).getOrCreate(o.price, levelPool).addVisible(o);
                }
                case AT_HIDDEN -> {
                    Order o = restoreOrder(in);
                    books[o.instrumentId].side(o.side).getOrCreate(o.price, levelPool).addHidden(o);
                }
                case AT_MID -> {
                    Order o = restoreOrder(in);
                    (o.side == Side.BUY ? books[o.instrumentId].midBuys : books[o.instrumentId].midSells).add(o);
                }
                case AT_NOWHERE -> restoreOrder(in);
                case AT_FIXING -> {
                    int instrument = in.readInt();
                    int event = in.readInt();
                    fixing.restoreResting(restoreOrder(in), instrument, event);
                }
                case AT_RACE -> {
                    long raceId = in.readLong();
                    int account = in.readInt();
                    races.restoreWaiting(restoreOrder(in), raceId, account);
                }
                default -> throw new IOException("Unknown order location " + at + " in snapshot");
            }
        }
        for (OrderBook book : books) {
            if (book != null) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    pegs.link(book, orders.get(in.readLong()));
                }
            }
        }
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
        SessionState session = books[c.instrumentId].session();
        if (session != SessionState.OPEN && session != SessionState.PRE_OPEN) {
            return session == SessionState.HALTED ? RejectReason.MARKET_HALTED : RejectReason.MARKET_NOT_OPEN;
        }
        if (session == SessionState.PRE_OPEN && c.type != OrderType.LIMIT && c.type != OrderType.ICEBERG) {
            return RejectReason.ORDER_TYPE_NOT_ALLOWED_IN_PRE_OPEN;
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
        if (c.type == OrderType.MARKET && c.tif != TimeInForce.IOC && c.tif != TimeInForce.FOK) {
            return RejectReason.INVALID_TIF_FOR_TYPE; // a market order cannot rest
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
        OrderBook book = books[o.instrumentId];
        if (book.isPreOpen() && (o.type == OrderType.LIMIT || o.type == OrderType.ICEBERG)) {
            // Collected or resting orders can be amended before the open.
        } else if (!book.isOpen()) {
            return RequestRejectReason.MARKET_NOT_OPEN;
        } else if (o.queue != null && o.type != OrderType.MID) {
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
            terminate(o, simulator.hitSelfTrade() ? CancelReason.SELF_TRADE : CancelReason.FOK_UNFILLED, time);
            return;
        }
        match(o, book, time);
        if (o.residualCancelQty > 0) {
            long qty = o.residualCancelQty;
            o.residualCancelQty = 0;
            listener.onOrderCancelled(time, o.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, qty, 0);
            release(o, time);
            return;
        }
        if (o.leaves == 0) {
            release(o, time);
            return;
        }
        if (o.selfTradeHit) {
            o.selfTradeHit = false;
            terminate(o, CancelReason.SELF_TRADE, time);
            return;
        }
        if (o.type == OrderType.MARKET) {
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
        if (o.tif == TimeInForce.GTT && time >= o.expireTime) {
            terminate(o, CancelReason.EXPIRED, time); // expired at the same instant the race closed
            return;
        }
        applyingRace = true;
        try {
            processBook(o, book, time);
        } finally {
            applyingRace = false;
        }
        sweepNewlyDisplayed(time); // each lottery order settles before the next is applied
    }

    /**
     * The taker race a request on this order must wait for under queue-cancels, or null. Only the side
     * being taken is held: a race of sell aggressors holds resting bids. An order waiting in a taker race
     * is held by its own race, so a taker cannot withdraw once it has entered.
     */
    private RaceManager.Race holdingRace(Order o, OrderBook book) {
        if (!book.instrument.queueCancels()) {
            return null;
        }
        if (o.inTakerRace) {
            return book.takerRaces[o.side.ordinal()];
        }
        return o.level == null ? null : book.takerRaces[o.side.opposite().ordinal()];
    }

    /** Records that an order's queued request also waits for {@code race} to resolve. */
    private void hold(Order o, OrderBook book, RaceManager.Race race) {
        if (race == null || o.heldByRace) {
            return;
        }
        o.heldByRace = true;
        o.holdUntilRace = race.id;
        o.holdRaceExpiry = race.expiry;
        if (book.heldCount == book.heldIds.length) {
            book.heldIds = java.util.Arrays.copyOf(book.heldIds, book.heldCount * 2);
        }
        book.heldIds[book.heldCount++] = o.orderId;
    }

    /** Best estimate of when a queued request will apply: TTL expiry or the end of the taker race. */
    private static long queuedUntil(Order o) {
        return o.heldByRace ? Math.max(o.pendingRelease, o.holdRaceExpiry) : o.pendingRelease;
    }

    /**
     * Called when taker race {@code raceId} resolves: releases requests held for it, applying those whose
     * min TTL has also passed. Other requests stay held, so back-to-back races cannot starve a cancel
     * beyond the race that was running when it arrived.
     */
    void releaseRaceHolds(OrderBook book, long raceId, long time) {
        int n = book.heldCount;
        int kept = 0;
        for (int i = 0; i < n; i++) {
            long id = book.heldIds[i];
            Order o = orders.get(id);
            if (o == null || !o.heldByRace) {
                continue;
            }
            if (o.holdUntilRace != raceId) {
                book.heldIds[kept++] = id;
                continue;
            }
            o.heldByRace = false;
            if (time >= o.pendingRelease && o.pendingType != null) {
                applyPending(o, time);
            } // otherwise its TTL timer applies it
        }
        book.heldCount = kept;
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
                if (agg.selfTradeHit) {
                    return;
                }
            }
            if (!unlocked || agg.leaves == 0 || agg.selfTradeHit) {
                return;
            }
        }
        int i = 0;
        while (i < opp.size() && agg.leaves > 0 && !agg.selfTradeHit) {
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
        while (p != null && agg.leaves > 0 && !agg.selfTradeHit) {
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
        while (p != null && agg.leaves > 0 && !agg.selfTradeHit) {
            Order next = p.next;
            tryFill(agg, p, level, book, time);
            p = next;
        }
    }

    /** Fills as much as the queue, both orders and credit allow. False if this order must be skipped. */
    private boolean tryFill(Order agg, Order passive, PriceLevel level, OrderBook book, long time) {
        if (credit.sameEntity(agg.accountId, passive.accountId)) {
            agg.selfTradeHit = true; // checked before credit: self-trade is not a credit outcome
            return false;
        }
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
    public static long roundDownToExecutionSize(long qty, Instrument instrument) {
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
            listener.onOrderCancelled(time, passive.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, residual, 0);
            release(passive, time);
        } else if (passive.inVisibleQueue && passive.displayQty < minQty) {
            // Slice exhausted (or below a fillable size): refresh to the back of the queue with new priority.
            level.remove(passive);
            passive.displayQty = Math.min(passive.maxShow, passive.leaves);
            passive.prioritySeq = ++lastPrioritySeq;
            level.addVisible(passive);
            noteDisplayed(passive, book);
        }
    }

    /** Remembers an order that newly displayed quantity, if hidden liquidity could trade with it. */
    private void noteDisplayed(Order o, OrderBook book) {
        if (book.instrument.matchPriority() != MatchPriority.VISIBILITY || !hiddenCrosses(o, book)) {
            return;
        }
        if (newlyDisplayedCount == newlyDisplayed.length) {
            newlyDisplayed = java.util.Arrays.copyOf(newlyDisplayed, newlyDisplayedCount * 2);
        }
        newlyDisplayed[newlyDisplayedCount++] = o.orderId;
    }

    private static boolean hiddenCrosses(Order o, OrderBook book) {
        BookSide opp = book.side(o.side.opposite());
        for (int i = 0; i < opp.size(); i++) {
            PriceLevel level = opp.levelAt(i);
            if (!crosses(o, level.price)) {
                return false;
            }
            if (level.hidHead != null) {
                return true;
            }
        }
        return false;
    }

    /** Lets every order noted by {@link #noteDisplayed} trade with hidden liquidity now the current order is done. */
    void sweepNewlyDisplayed(long time) {
        for (int i = 0; i < newlyDisplayedCount; i++) {
            Order o = orders.get(newlyDisplayed[i]);
            if (o != null && o.level != null && o.inVisibleQueue && o.displayQty > 0) {
                sweepHidden(o, books[o.instrumentId], time);
            }
        }
        newlyDisplayedCount = 0;
    }

    /**
     * VISIBILITY: a resting order's displayed quantity trades at once with hidden orders that cross it,
     * best price then time, at the hidden order's price. The resting order counts as the aggressor for
     * self-trade prevention. A refreshed iceberg slice keeps sweeping until it or the hidden side is done.
     */
    private void sweepHidden(Order o, OrderBook book, long time) {
        BookSide opp = book.side(o.side.opposite());
        Instrument instrument = book.instrument;
        int i = 0;
        while (o.level != null && i < opp.size()) {
            PriceLevel hidden = opp.levelAt(i);
            if (!crosses(o, hidden.price)) {
                return;
            }
            Order p = hidden.hidHead;
            while (p != null && o.level != null) {
                Order next = p.next;
                if (credit.sameEntity(o.accountId, p.accountId)) {
                    cancelResting(o, CancelReason.SELF_TRADE, time);
                    return;
                }
                long qty = Math.min(o.displayQty, p.leaves);
                long cap = credit.capacity(o.accountId, p.accountId, instrument.id(), o.side);
                if (cap < qty) {
                    qty = roundDownToExecutionSize(cap, instrument);
                }
                if (qty > 0) {
                    fillRestingWithHidden(o, p, hidden, book, time, qty);
                    if (p.level == hidden && o.level != null) {
                        continue; // a refreshed iceberg tip keeps trading with the same hidden order
                    }
                }
                p = next;
            }
            if (hidden.isEmpty()) {
                opp.removeAt(i, levelPool);
            } else {
                i++;
            }
        }
    }

    private void fillRestingWithHidden(Order o, Order hiddenOrder, PriceLevel hidden, OrderBook book, long time, long qty) {
        PriceLevel own = o.level;
        o.displayQty -= qty;
        own.visibleQty -= qty;
        if (o.type == OrderType.PEGGED) {
            own.pegVisibleQty -= qty;
        }
        fill(o, hiddenOrder, hidden, book, time, qty); // reduces o.leaves, and so its reserve stays consistent
        long minQty = book.instrument.minQty();
        if (o.residualCancelQty > 0) {
            long residual = o.residualCancelQty;
            o.residualCancelQty = 0;
            o.leaves = residual; // level accounting still counts it
            removeFromBook(o);
            o.leaves = 0;
            listener.onOrderCancelled(time, o.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, residual, 0);
            release(o, time);
        } else if (o.leaves == 0) {
            removeFromBook(o);
            release(o, time);
        } else if (o.displayQty < minQty) {
            own.remove(o);
            o.displayQty = Math.min(o.maxShow, o.leaves);
            o.prioritySeq = ++lastPrioritySeq;
            own.addVisible(o);
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
            noteDisplayed(o, book);
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
                book.version++; // callers (mass cancel, end of day, race expiry) may have changed any book
                mid.check(book, time);
            }
        }
    }

    private void requestCancel(Order o, CancelSource source, long requestId, long time) {
        if (source != CancelSource.PARTICIPANT) {
            cancelResting(o, source.reason(), time); // any queued request is answered TOO_LATE
            return;
        }
        if (o.pendingType == RequestType.CANCEL) {
            listener.onRequestRejected(time, o.orderId, RequestType.CANCEL, RequestRejectReason.CANCEL_ALREADY_PENDING,
                    requestId);
            return;
        }
        OrderBook book = books[o.instrumentId];
        if (book.isPreOpen()) {
            o.cancelRequestId = requestId;
            cancelResting(o, CancelReason.PARTICIPANT, time); // no TTL restriction before the open
            return;
        }
        Instrument instrument = book.instrument;
        long release = o.ttlStart + ttlFor(o, instrument);
        RaceManager.Race holding = holdingRace(o, book);
        boolean raceHold = holding != null;
        if (time < release && instrument.rejectOnTtl()) {
            // Inside TTL with reject-on-ttl: rejected even if a race would otherwise hold it (PLAN Q15 default).
            listener.onRequestRejected(time, o.orderId, RequestType.CANCEL, RequestRejectReason.INSIDE_MIN_TTL, requestId);
            return;
        }
        if (time < release || raceHold) {
            if (o.pendingType == RequestType.REPLACE) {
                listener.onRequestRejected(time, o.orderId, RequestType.REPLACE, RequestRejectReason.SUPERSEDED,
                        o.pendingRequestId);
            } else if (time < release) {
                timers.schedule(release, TIMER_TTL, o.orderId);
            }
            o.pendingType = RequestType.CANCEL;
            o.pendingRequestId = requestId;
            o.pendingRelease = release;
            hold(o, book, holding);
            listener.onRequestQueued(time, o.orderId, RequestType.CANCEL, queuedUntil(o), requestId);
            return;
        }
        o.cancelRequestId = requestId;
        cancelResting(o, CancelReason.PARTICIPANT, time);
    }

    private void applyReplace(Order o, long price, long qty, long maxShow, long discretion, long requestId, long time) {
        OrderBook book = books[o.instrumentId];
        long newLeaves = qty - o.cumQty;
        o.ttlStart = time; // an applied amend restarts min TTL (confirmed, PLAN Q14a)
        if (o.queue != null && book.isPreOpen()) {
            // Waiting for the opening lottery: losing priority moves it to the back of its ticket.
            OrderQueue ticket = o.queue;
            boolean losesPriority = price != o.price || qty > o.qty || maxShow != o.maxShow;
            o.price = price;
            o.qty = qty;
            o.leaves = newLeaves;
            o.maxShow = maxShow;
            if (losesPriority) {
                ticket.remove(o);
                ticket.add(o);
            }
            listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
            return;
        }
        if (o.type == OrderType.MID) {
            if (qty <= o.qty && discretion == o.discretion) {
                o.qty = qty;
                o.leaves = newLeaves;
                listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
                return;
            }
            o.queue.remove(o);
            o.qty = qty;
            o.leaves = newLeaves;
            o.discretion = discretion;
            listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
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
                listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
                return;
            }
        }
        boolean priceChanged = price != o.price;
        boolean keepPriority = !priceChanged && qty <= o.qty && maxShow == o.maxShow;
        if (keepPriority) {
            o.level.adjust(o, newLeaves, o.inVisibleQueue ? Math.min(o.displayQty, newLeaves) : 0);
            o.qty = qty;
            listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
            return;
        }
        PriceLevel level = o.level;
        level.remove(o);
        book.side(o.side).removeIfEmpty(level, levelPool);
        o.price = price;
        o.qty = qty;
        o.leaves = newLeaves;
        o.maxShow = maxShow;
        listener.onOrderReplaced(time, o.orderId, o.price, o.qty, o.leaves, requestId);
        if (book.isPreOpen()) {
            races.joinPreOpen(o, book, time);
            return;
        }
        o.fresh = priceChanged && o.type != OrderType.PEGGED; // an amend to a new price can create a level and start a maker race
        process(o, book, time);
        sweepNewlyDisplayed(time);
    }

    /** Cancels an order that is not resting (an aggressor that will not rest). */
    void terminate(Order o, CancelReason reason, long time) {
        long qty = o.leaves;
        o.leaves = 0;
        listener.onOrderCancelled(time, o.orderId, reason, qty, o.cancelRequestId);
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
            listener.onRequestRejected(time, o.orderId, o.pendingType, RequestRejectReason.TOO_LATE, o.pendingRequestId);
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
            book.version++;
            pegs.onTick(book, deadline);
            mid.check(book, deadline);
            return;
        }
        if (kind == TIMER_FIXING) {
            fixing.onCutoff((int) id, deadline); // fixing orders are never displayed
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
        book.version++;
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
        long requestId = o.pendingRequestId;
        o.pendingType = null;
        if (type == RequestType.CANCEL) {
            o.cancelRequestId = requestId;
            cancelResting(o, CancelReason.PARTICIPANT, deadline);
            return;
        }
        // Re-validate: the order may have filled while the replace was queued.
        RequestRejectReason reason = validateReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow);
        if (reason != null) {
            listener.onRequestRejected(deadline, o.orderId, RequestType.REPLACE, reason, requestId);
        } else {
            applyReplace(o, o.pendingPrice, o.pendingQty, o.pendingMaxShow, o.pendingDiscretion, requestId, deadline);
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
