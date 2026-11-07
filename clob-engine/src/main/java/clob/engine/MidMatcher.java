package clob.engine;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.TimeInForce;

/**
 * Mid orders: a separate book per instrument, matching only other mid orders at the visible mid.
 *
 * <ul>
 *   <li>The mid comes from the visible top of book excluding peg orders, the same reference pegs use
 *       (PLAN Q34 default). With no visible bid or no visible offer, mid orders cannot match; they
 *       rest rather than being rejected.
 *   <li>Mid trades may execute at <b>one decimal place beyond the instrument's precision</b> (confirmed,
 *       2026-11-07), so the half-tick mid of any two prices is exact and nothing is rounded. Arithmetic
 *       is in tenths of a tick: {@code mid10 = (bid + ask) × 5}.
 *   <li>Effective limits: buy = mid + discretion, sell = mid − discretion (pips; positive = more
 *       aggressive, confirmed). A buy and a sell cross when buyLimit ≥ sellLimit, and trade at
 *       {@code clamp(mid, sellLimit, buyLimit)}, reported with {@code pricePrecision + 1} decimals.
 *       So a 92/93 market matches two zero-discretion mid orders at 92.5.
 *   <li>Priority is time among crossing orders (Q36 default). A new mid order aggresses against resting
 *       ones. Whether two orders cross depends only on their discretions, not on where the mid is, so
 *       resting orders are re-checked when the reference becomes available again (or moves): oldest buy
 *       first, each against sells oldest first; the later of the two is the aggressor.
 *   <li>Credit and minimum-quantity rules are the same as the main book.
 * </ul>
 */
final class MidMatcher {
    private static final long NO_PRICE = Long.MIN_VALUE;
    /** Mid prices carry one extra decimal place. */
    static final long SUB_TICKS = 10;

    private final MatchingEngine engine;

    MidMatcher(MatchingEngine engine) {
        this.engine = engine;
    }

    /** A new or re-entering mid order. */
    void process(Order o, OrderBook book, long time) {
        long bid = book.referenceBid();
        long ask = book.referenceAsk();
        boolean live = bid != NO_PRICE && ask != NO_PRICE;
        if (o.tif == TimeInForce.FOK && (!live || fillable(o, book, mid10(bid, ask)) < o.leaves)) {
            engine.terminate(o, CancelReason.FOK_UNFILLED, time);
            return;
        }
        if (live) {
            aggress(o, book, mid10(bid, ask), time);
        }
        if (o.residualCancelQty > 0) {
            long qty = o.residualCancelQty;
            o.residualCancelQty = 0;
            engine.listener.onOrderCancelled(time, o.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, qty);
            engine.release(o, time);
            return;
        }
        if (o.leaves == 0) {
            engine.release(o, time);
            return;
        }
        switch (o.tif) {
            case IOC -> engine.terminate(o, CancelReason.IOC_UNFILLED, time);
            case FOK -> throw new IllegalStateException("Mid FOK simulation disagreed for order " + o.orderId);
            default -> {
                o.prioritySeq = engine.nextPrioritySeq();
                queue(book, o.side).add(o);
                engine.scheduleGtt(o);
            }
        }
    }

    /** Re-checks resting mid orders after the reference may have moved. */
    void check(OrderBook book, long time) {
        if (book.midBuys.isEmpty() || book.midSells.isEmpty()) {
            return;
        }
        long bid = book.referenceBid();
        long ask = book.referenceAsk();
        if (bid == book.lastMidBid && ask == book.lastMidAsk) {
            return;
        }
        book.lastMidBid = bid;
        book.lastMidAsk = ask;
        if (bid == NO_PRICE || ask == NO_PRICE) {
            return;
        }
        long mid10 = mid10(bid, ask);
        for (Order b = book.midBuys.head; b != null; ) {
            Order nextBuy = b.next;
            for (Order s = book.midSells.head; s != null && b.queue != null; ) {
                Order nextSell = s.next;
                long buyLimit = limit10(b, mid10);
                long sellLimit = limit10(s, mid10);
                if (buyLimit >= sellLimit) {
                    boolean buyIsLater = b.prioritySeq > s.prioritySeq;
                    Order agg = buyIsLater ? b : s;
                    Order passive = buyIsLater ? s : b;
                    tryFill(agg, passive, buyLimit, sellLimit, mid10, book, time, true);
                }
                s = nextSell;
            }
            b = nextBuy;
        }
    }

    private static OrderQueue queue(OrderBook book, Side side) {
        return side == Side.BUY ? book.midBuys : book.midSells;
    }

    /** Mid in tenths of a tick. */
    static long mid10(long bid, long ask) {
        return (bid + ask) * (SUB_TICKS / 2);
    }

    /** Effective limit in tenths of a tick: buy = mid + discretion, sell = mid - discretion. */
    static long limit10(Order o, long mid10) {
        return o.side == Side.BUY ? mid10 + o.discretion * SUB_TICKS : mid10 - o.discretion * SUB_TICKS;
    }

    /** {@code clamp(mid, sellLimit, buyLimit)}, all in tenths of a tick. */
    static long tradePrice10(long mid10, long buyLimit10, long sellLimit10) {
        return Math.max(sellLimit10, Math.min(buyLimit10, mid10));
    }

    private void aggress(Order agg, OrderBook book, long mid10, long time) {
        long aggLimit = limit10(agg, mid10);
        OrderQueue opp = queue(book, agg.side.opposite());
        for (Order p = opp.head; p != null && agg.leaves > 0; ) {
            Order next = p.next;
            long pLimit = limit10(p, mid10);
            long buyLimit = agg.side == Side.BUY ? aggLimit : pLimit;
            long sellLimit = agg.side == Side.BUY ? pLimit : aggLimit;
            if (buyLimit >= sellLimit) {
                tryFill(agg, p, buyLimit, sellLimit, mid10, book, time, false);
            }
            p = next;
        }
    }

    private void tryFill(Order agg, Order passive, long buyLimit, long sellLimit, long mid10, OrderBook book,
            long time, boolean aggressorResting) {
        Instrument instrument = book.instrument;
        long qty = Math.min(agg.leaves, passive.leaves);
        long cap = engine.credit.capacity(agg.accountId, passive.accountId, instrument.id(), agg.side);
        if (cap < qty) {
            qty = MatchingEngine.roundDownToExecutionSize(cap, instrument);
            if (qty == 0) {
                return;
            }
        }
        long price = tradePrice10(mid10, buyLimit, sellLimit);
        engine.credit.commit(agg.accountId, passive.accountId, instrument.id(), agg.side, qty);
        agg.leaves -= qty;
        agg.cumQty += qty;
        passive.leaves -= qty;
        passive.cumQty += qty;
        engine.listener.onTrade(time, engine.nextTradeId(), instrument.id(), agg.orderId, passive.orderId, agg.side,
                price, instrument.pricePrecision() + 1, qty, agg.leaves, passive.leaves);
        afterFill(passive, instrument, time);
        if (aggressorResting) {
            afterFill(agg, instrument, time);
        } else if (agg.leaves > 0 && agg.leaves < instrument.minQty()) {
            agg.residualCancelQty = agg.leaves;
            agg.leaves = 0;
        }
    }

    /** Removes a resting mid order that is filled or left below min qty. */
    private void afterFill(Order o, Instrument instrument, long time) {
        if (o.leaves == 0) {
            o.queue.remove(o);
            engine.release(o, time);
        } else if (o.leaves < instrument.minQty()) {
            o.queue.remove(o);
            long residual = o.leaves;
            o.leaves = 0;
            engine.listener.onOrderCancelled(time, o.orderId, CancelReason.RESIDUAL_BELOW_MIN_QTY, residual);
            engine.release(o, time);
        }
    }

    /** Dry run of {@link #aggress} for FOK, with credit simulated. */
    private long fillable(Order agg, OrderBook book, long mid10) {
        Instrument instrument = book.instrument;
        long aggLimit = limit10(agg, mid10);
        long remaining = agg.leaves;
        engine.credit.beginSimulation();
        try {
            for (Order p = queue(book, agg.side.opposite()).head; p != null && remaining > 0; p = p.next) {
                long pLimit = limit10(p, mid10);
                long buyLimit = agg.side == Side.BUY ? aggLimit : pLimit;
                long sellLimit = agg.side == Side.BUY ? pLimit : aggLimit;
                if (buyLimit < sellLimit) {
                    continue;
                }
                long qty = Math.min(remaining, p.leaves);
                long cap = engine.credit.capacity(agg.accountId, p.accountId, instrument.id(), agg.side);
                if (cap < qty) {
                    qty = MatchingEngine.roundDownToExecutionSize(cap, instrument);
                    if (qty == 0) {
                        continue;
                    }
                }
                engine.credit.commit(agg.accountId, p.accountId, instrument.id(), agg.side, qty);
                remaining -= qty;
                if (remaining > 0 && remaining < instrument.minQty()) {
                    break; // the real aggressor's residual would be cancelled
                }
            }
        } finally {
            engine.credit.endSimulation();
        }
        return agg.leaves - remaining;
    }
}
