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
 *   <li>Effective limits: buy = mid + discretion, sell = mid − discretion (pips; positive = more
 *       aggressive, confirmed). Discretion is applied to the raw mid (which may be half a tick), then
 *       rounded defensively to the price increment: bid limits down, offer limits up (Q3b default).
 *   <li>A buy and a sell cross when buyLimit ≥ sellLimit. Trade price = the valid price nearest the raw
 *       mid within [sellLimit, buyLimit], which is {@code clamp(mid, sellLimit, buyLimit)} when the mid
 *       is on the increment. On a tie, the resting (earlier) order gets the better price.
 *   <li>Priority is time among crossing orders (Q36 default). A new mid order aggresses against resting
 *       ones. When the reference moves, resting mid orders are re-checked against each other: oldest
 *       buy first, each against sells oldest first; the later of the two is the aggressor.
 *   <li>Credit and minimum-quantity rules are the same as the main book.
 * </ul>
 */
final class MidMatcher {
    private static final long NO_PRICE = Long.MIN_VALUE;

    private final MatchingEngine engine;

    MidMatcher(MatchingEngine engine) {
        this.engine = engine;
    }

    /** A new or re-entering mid order. */
    void process(Order o, OrderBook book, long time) {
        long bid = book.referenceBid();
        long ask = book.referenceAsk();
        boolean live = bid != NO_PRICE && ask != NO_PRICE;
        if (o.tif == TimeInForce.FOK && (!live || fillable(o, book, bid + ask) < o.leaves)) {
            engine.terminate(o, CancelReason.FOK_UNFILLED, time);
            return;
        }
        if (live) {
            aggress(o, book, bid + ask, time);
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
        long twiceMid = bid + ask;
        long inc = book.instrument.priceIncrement();
        for (Order b = book.midBuys.head; b != null; ) {
            Order nextBuy = b.next;
            for (Order s = book.midSells.head; s != null && b.queue != null; ) {
                Order nextSell = s.next;
                long buyLimit = buyLimit(twiceMid, b.discretion, inc);
                long sellLimit = sellLimit(twiceMid, s.discretion, inc);
                if (buyLimit >= sellLimit) {
                    boolean buyIsLater = b.prioritySeq > s.prioritySeq;
                    Order agg = buyIsLater ? b : s;
                    Order passive = buyIsLater ? s : b;
                    tryFill(agg, passive, buyLimit, sellLimit, twiceMid, book, time, true);
                }
                s = nextSell;
            }
            b = nextBuy;
        }
    }

    private static OrderQueue queue(OrderBook book, Side side) {
        return side == Side.BUY ? book.midBuys : book.midSells;
    }

    private static long halfDown(long twice) {
        return Math.floorDiv(twice, 2);
    }

    private static long halfUp(long twice) {
        return -Math.floorDiv(-twice, 2);
    }

    private static long roundDown(long price, long inc) {
        return Math.floorDiv(price, inc) * inc;
    }

    private static long roundUp(long price, long inc) {
        return -Math.floorDiv(-price, inc) * inc;
    }

    static long buyLimit(long twiceMid, long discretion, long inc) {
        return roundDown(halfDown(twiceMid + 2 * discretion), inc);
    }

    static long sellLimit(long twiceMid, long discretion, long inc) {
        return roundUp(halfUp(twiceMid - 2 * discretion), inc);
    }

    private static long limit(Order o, long twiceMid, long inc) {
        return o.side == Side.BUY ? buyLimit(twiceMid, o.discretion, inc) : sellLimit(twiceMid, o.discretion, inc);
    }

    static long tradePrice(long twiceMid, long buyLimit, long sellLimit, Side passiveSide, long inc) {
        long low = roundDown(halfDown(twiceMid), inc);
        long high = roundUp(halfUp(twiceMid), inc);
        long lowClamped = Math.max(sellLimit, Math.min(buyLimit, low));
        long highClamped = Math.max(sellLimit, Math.min(buyLimit, high));
        if (lowClamped == highClamped) {
            return lowClamped;
        }
        long lowDistance = Math.abs(2 * lowClamped - twiceMid);
        long highDistance = Math.abs(2 * highClamped - twiceMid);
        if (lowDistance != highDistance) {
            return lowDistance < highDistance ? lowClamped : highClamped;
        }
        return passiveSide == Side.BUY ? lowClamped : highClamped;
    }

    private void aggress(Order agg, OrderBook book, long twiceMid, long time) {
        long inc = book.instrument.priceIncrement();
        long aggLimit = limit(agg, twiceMid, inc);
        OrderQueue opp = queue(book, agg.side.opposite());
        for (Order p = opp.head; p != null && agg.leaves > 0; ) {
            Order next = p.next;
            long pLimit = limit(p, twiceMid, inc);
            long buyLimit = agg.side == Side.BUY ? aggLimit : pLimit;
            long sellLimit = agg.side == Side.BUY ? pLimit : aggLimit;
            if (buyLimit >= sellLimit) {
                tryFill(agg, p, buyLimit, sellLimit, twiceMid, book, time, false);
            }
            p = next;
        }
    }

    private void tryFill(Order agg, Order passive, long buyLimit, long sellLimit, long twiceMid, OrderBook book,
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
        long price = tradePrice(twiceMid, buyLimit, sellLimit, passive.side, instrument.priceIncrement());
        engine.credit.commit(agg.accountId, passive.accountId, instrument.id(), agg.side, qty);
        agg.leaves -= qty;
        agg.cumQty += qty;
        passive.leaves -= qty;
        passive.cumQty += qty;
        engine.listener.onTrade(time, engine.nextTradeId(), instrument.id(), agg.orderId, passive.orderId, agg.side,
                price, qty, agg.leaves, passive.leaves);
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
    private long fillable(Order agg, OrderBook book, long twiceMid) {
        Instrument instrument = book.instrument;
        long inc = instrument.priceIncrement();
        long aggLimit = limit(agg, twiceMid, inc);
        long remaining = agg.leaves;
        engine.credit.beginSimulation();
        try {
            for (Order p = queue(book, agg.side.opposite()).head; p != null && remaining > 0; p = p.next) {
                long pLimit = limit(p, twiceMid, inc);
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
