package clob.engine;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Fixing orders. Each fixing event has at most one open occurrence, opened by a sequenced
 * {@code onFixingSchedule} input (cut-off and fixing instants are computed outside the engine, from
 * the event's time zone). For each (instrument, event):
 *
 * <ol>
 *   <li><b>Until cut-off:</b> fixing orders match only each other, continuously, in time priority,
 *       subject to credit and minimum quantity (PLAN Q5e default). A match is reported straight away,
 *       without a price ({@code onFixingMatched}, Q5f default). Credit is reserved at match time:
 *       exposure is notional × the base currency's USD rate, so the fixing price isn't needed (Q5b).
 *   <li><b>At cut-off:</b> unmatched quantity is cancelled ({@link CancelReason#FIXING_CUTOFF}).
 *   <li><b>When the rate arrives:</b> every pending match is reported as a trade at the rate (given
 *       at the instrument's price precision).
 * </ol>
 *
 * A new occurrence cannot be scheduled while the previous one still awaits its rate (Q5g default:
 * pending matches stay open until a rate is supplied). Fixing orders must rest (DAY, GTC or GTT) and
 * cannot be amended.
 */
final class FixingDesk {
    private final MatchingEngine engine;
    private final RefData refData;
    private final long[] cutoff;
    private final long[] fixingTime;
    private final boolean[] open;
    private final Session[][] sessions;

    static final class Session {
        final OrderQueue buys = new OrderQueue();
        final OrderQueue sells = new OrderQueue();
        boolean awaitingRate;
        int pending;
        long[] matchIds = new long[16];
        long[] aggressorIds = new long[16];
        long[] passiveIds = new long[16];
        long[] qtys = new long[16];
        Side[] aggressorSides = new Side[16];

        void record(long matchId, long aggressorId, long passiveId, long qty, Side side) {
            if (pending == matchIds.length) {
                int n = pending * 2;
                matchIds = Arrays.copyOf(matchIds, n);
                aggressorIds = Arrays.copyOf(aggressorIds, n);
                passiveIds = Arrays.copyOf(passiveIds, n);
                qtys = Arrays.copyOf(qtys, n);
                aggressorSides = Arrays.copyOf(aggressorSides, n);
            }
            matchIds[pending] = matchId;
            aggressorIds[pending] = aggressorId;
            passiveIds[pending] = passiveId;
            qtys[pending] = qty;
            aggressorSides[pending] = side;
            pending++;
        }
    }

    FixingDesk(MatchingEngine engine, RefData refData) {
        this.engine = engine;
        this.refData = refData;
        int events = refData.fixingEventCapacity();
        cutoff = new long[events];
        fixingTime = new long[events];
        open = new boolean[events];
        sessions = new Session[refData.instrumentCapacity()][events];
    }

    boolean schedule(int eventId, long cutoffTime, long fixingAt, long now) {
        if (refData.fixingEvent(eventId) == null || open[eventId] || cutoffTime <= now || fixingAt < cutoffTime) {
            return false;
        }
        for (Session[] perInstrument : sessions) {
            if (perInstrument[eventId] != null && perInstrument[eventId].awaitingRate) {
                return false;
            }
        }
        cutoff[eventId] = cutoffTime;
        fixingTime[eventId] = fixingAt;
        open[eventId] = true;
        engine.schedule(cutoffTime, MatchingEngine.TIMER_FIXING, eventId);
        return true;
    }

    RejectReason validate(NewOrderCommand c, long now) {
        if (refData.fixingEvent(c.fixingEventId) == null) {
            return RejectReason.UNKNOWN_FIXING_EVENT;
        }
        if (!open[c.fixingEventId] || now >= cutoff[c.fixingEventId]) {
            return RejectReason.FIXING_NOT_OPEN;
        }
        if (c.tif != TimeInForce.DAY && c.tif != TimeInForce.GTC && c.tif != TimeInForce.GTT) {
            return RejectReason.INVALID_TIF_FOR_TYPE;
        }
        if (c.maxShow != NewOrderCommand.FULLY_VISIBLE) {
            return RejectReason.INVALID_SHOW;
        }
        return null;
    }

    private Session session(int instrumentId, int eventId) {
        Session s = sessions[instrumentId][eventId];
        if (s == null) {
            s = new Session();
            sessions[instrumentId][eventId] = s;
        }
        return s;
    }

    void process(Order agg, OrderBook book, long time) {
        Instrument instrument = book.instrument;
        Session s = session(instrument.id(), agg.fixingEventId);
        OrderQueue opp = agg.side == Side.BUY ? s.sells : s.buys;
        for (Order p = opp.head; p != null && agg.leaves > 0; ) {
            Order next = p.next;
            if (engine.credit.sameEntity(agg.accountId, p.accountId)) {
                agg.selfTradeHit = true;
                break;
            }
            long qty = Math.min(agg.leaves, p.leaves);
            long cap = engine.credit.capacity(agg.accountId, p.accountId, instrument.id(), agg.side);
            if (cap < qty) {
                qty = MatchingEngine.roundDownToExecutionSize(cap, instrument);
            }
            if (qty > 0) {
                fill(agg, p, qty, s, book, time);
            }
            p = next;
        }
        if (agg.residualCancelQty > 0) {
            long qty = agg.residualCancelQty;
            agg.residualCancelQty = 0;
            engine.listener.onOrderCancelled(time, agg.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, qty, 0);
            engine.release(agg, time);
            return;
        }
        if (agg.leaves == 0) {
            engine.release(agg, time);
            return;
        }
        if (agg.selfTradeHit) {
            agg.selfTradeHit = false;
            engine.terminate(agg, CancelReason.SELF_TRADE, time);
            return;
        }
        agg.prioritySeq = engine.nextPrioritySeq();
        (agg.side == Side.BUY ? s.buys : s.sells).add(agg);
        engine.scheduleGtt(agg);
    }

    private void fill(Order agg, Order passive, long qty, Session s, OrderBook book, long time) {
        Instrument instrument = book.instrument;
        engine.credit.commit(agg.accountId, passive.accountId, instrument.id(), agg.side, qty);
        agg.leaves -= qty;
        agg.cumQty += qty;
        passive.leaves -= qty;
        passive.cumQty += qty;
        long matchId = engine.nextTradeId();
        s.record(matchId, agg.orderId, passive.orderId, qty, agg.side);
        engine.listener.onFixingMatched(time, matchId, instrument.id(), agg.fixingEventId, agg.orderId,
                passive.orderId, agg.side, qty, agg.leaves, passive.leaves);
        if (agg.leaves > 0 && agg.leaves < instrument.minQty()) {
            agg.residualCancelQty = agg.leaves;
            agg.leaves = 0;
        }
        if (passive.leaves == 0) {
            passive.queue.remove(passive);
            engine.release(passive, time);
        } else if (passive.leaves < instrument.minQty()) {
            passive.queue.remove(passive);
            long residual = passive.leaves;
            passive.leaves = 0;
            engine.listener.onOrderCancelled(time, passive.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, residual, 0);
            engine.release(passive, time);
        }
    }

    void onCutoff(int eventId, long deadline) {
        if (!open[eventId] || cutoff[eventId] != deadline) {
            return;
        }
        open[eventId] = false;
        for (Session[] perInstrument : sessions) {
            Session s = perInstrument[eventId];
            if (s == null) {
                continue;
            }
            while (s.buys.head != null) {
                engine.cancelResting(s.buys.head, CancelReason.FIXING_CUTOFF, deadline);
            }
            while (s.sells.head != null) {
                engine.cancelResting(s.sells.head, CancelReason.FIXING_CUTOFF, deadline);
            }
            s.awaitingRate = s.pending > 0;
        }
    }

    /**
     * Prices pending matches at {@code rate}. False if nothing awaits a rate for this event and
     * instrument, or if the fixing time has not been reached (an early rate is refused; the source resends).
     */
    boolean rate(int eventId, int instrumentId, long rate, long time) {
        if (eventId < 0 || eventId >= open.length || instrumentId < 0 || instrumentId >= sessions.length || rate <= 0) {
            return false;
        }
        Session s = sessions[instrumentId][eventId];
        if (s == null || !s.awaitingRate || time < fixingTime[eventId]) {
            return false;
        }
        for (int k = 0; k < s.pending; k++) {
            engine.listener.onTrade(time, s.matchIds[k], instrumentId, s.aggressorIds[k], s.passiveIds[k],
                    s.aggressorSides[k], rate, refData.instrument(instrumentId).pricePrecision(), s.qtys[k], -1, -1);
            s.aggressorSides[k] = null;
        }
        s.pending = 0;
        s.awaitingRate = false;
        return true;
    }

    interface OrderWriter {
        void write(Order o, int instrumentId, int eventId) throws java.io.IOException;
    }

    void saveMeta(java.io.DataOutput out) throws java.io.IOException {
        for (int e = 0; e < open.length; e++) {
            out.writeLong(cutoff[e]);
            out.writeLong(fixingTime[e]);
            out.writeBoolean(open[e]);
        }
        int count = 0;
        for (Session[] perInstrument : sessions) {
            for (Session s : perInstrument) {
                count += s == null ? 0 : 1;
            }
        }
        out.writeInt(count);
        for (int i = 0; i < sessions.length; i++) {
            for (int e = 0; e < sessions[i].length; e++) {
                Session s = sessions[i][e];
                if (s == null) {
                    continue;
                }
                out.writeInt(i);
                out.writeInt(e);
                out.writeBoolean(s.awaitingRate);
                out.writeInt(s.pending);
                for (int k = 0; k < s.pending; k++) {
                    out.writeLong(s.matchIds[k]);
                    out.writeLong(s.aggressorIds[k]);
                    out.writeLong(s.passiveIds[k]);
                    out.writeLong(s.qtys[k]);
                    out.writeUTF(s.aggressorSides[k].name());
                }
            }
        }
    }

    void forEachResting(OrderWriter writer) throws java.io.IOException {
        for (int i = 0; i < sessions.length; i++) {
            for (int e = 0; e < sessions[i].length; e++) {
                Session s = sessions[i][e];
                if (s == null) {
                    continue;
                }
                for (Order o = s.buys.head; o != null; o = o.next) {
                    writer.write(o, i, e);
                }
                for (Order o = s.sells.head; o != null; o = o.next) {
                    writer.write(o, i, e);
                }
            }
        }
    }

    void restoreMeta(java.io.DataInput in) throws java.io.IOException {
        for (int e = 0; e < open.length; e++) {
            cutoff[e] = in.readLong();
            fixingTime[e] = in.readLong();
            open[e] = in.readBoolean();
        }
        int count = in.readInt();
        for (int n = 0; n < count; n++) {
            int instrument = in.readInt();
            int event = in.readInt();
            Session s = session(instrument, event);
            s.awaitingRate = in.readBoolean();
            int pending = in.readInt();
            for (int k = 0; k < pending; k++) {
                long matchId = in.readLong();
                long aggressor = in.readLong();
                long passive = in.readLong();
                long qty = in.readLong();
                s.record(matchId, aggressor, passive, qty, Side.valueOf(in.readUTF()));
            }
        }
    }

    void restoreResting(Order o, int instrumentId, int eventId) {
        Session s = session(instrumentId, eventId);
        (o.side == Side.BUY ? s.buys : s.sells).add(o);
    }

    void collect(Predicate<Order> filter, ArrayList<Order> out) {
        for (Session[] perInstrument : sessions) {
            for (Session s : perInstrument) {
                if (s == null) {
                    continue;
                }
                for (Order o = s.buys.head; o != null; o = o.next) {
                    if (filter.test(o)) {
                        out.add(o);
                    }
                }
                for (Order o = s.sells.head; o != null; o = o.next) {
                    if (filter.test(o)) {
                        out.add(o);
                    }
                }
            }
        }
    }
}
