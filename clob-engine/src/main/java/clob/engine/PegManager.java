package clob.engine;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.PegReference;
import java.util.Arrays;

/**
 * Pegged orders. A peg tracks the visible top of book <em>excluding peg orders</em> (so pegs never
 * chase each other), offset by discretion in pips (positive = more aggressive). It is priced on entry,
 * then repriced only on the instrument's peg-throttle grid ({@code t = k × pegThrottle}), from a
 * reference frozen at the start of each tick. Pegs are processed in arrival order.
 *
 * <ul>
 *   <li>New pegs are rejected if either side has no visible non-peg price; resting pegs are suspended
 *       (taken off the book, not cancelled) at a tick where either side is missing, and resume at the
 *       first tick where both are back (confirmed, PLAN Q4c).
 *   <li>A reprice or resumption gives the order new time priority (confirmed, Q4b). A tick that leaves
 *       the price unchanged keeps priority (Q22 default). A reprice does not restart min TTL (Q22 default).
 *   <li>A repriced peg that crosses trades as an aggressor (confirmed, Q4a).
 *   <li>Rounding (Q3b/Q4f default): discretion is applied to the raw reference (the mid may be half a
 *       tick), then the result is rounded defensively to the price increment: bids down, offers up.
 * </ul>
 */
final class PegManager {
    static final long NO_PRICE = Long.MIN_VALUE;

    private final MatchingEngine engine;
    private long[] snapshot = new long[64];

    PegManager(MatchingEngine engine) {
        this.engine = engine;
    }

    static boolean referenceAvailable(OrderBook book) {
        return book.referenceBid() != NO_PRICE && book.referenceAsk() != NO_PRICE;
    }

    static long pegPrice(Side side, PegReference reference, long discretion, long bid, long ask, Instrument instrument) {
        if (bid == NO_PRICE || ask == NO_PRICE) {
            return NO_PRICE;
        }
        long inc = instrument.priceIncrement();
        long price;
        if (reference == PegReference.NEAR_TOUCH) {
            price = side == Side.BUY ? bid + discretion : ask - discretion;
        } else {
            long twiceMid = bid + ask;
            price = side == Side.BUY
                    ? Math.floorDiv(twiceMid + 2 * discretion, 2)
                    : -Math.floorDiv(-(twiceMid - 2 * discretion), 2);
        }
        price = side == Side.BUY ? Math.floorDiv(price, inc) * inc : -Math.floorDiv(-price, inc) * inc;
        return instrument.isValidPrice(price) ? price : NO_PRICE;
    }

    static long pegPrice(Order o, OrderBook book) {
        return pegPrice(o.side, o.pegReference, o.discretion, book.referenceBid(), book.referenceAsk(), book.instrument);
    }

    void link(OrderBook book, Order o) {
        o.pegPrev = book.pegTail;
        o.pegNext = null;
        if (book.pegTail == null) {
            book.pegHead = o;
        } else {
            book.pegTail.pegNext = o;
        }
        book.pegTail = o;
        o.inPegList = true;
        book.pegCount++;
    }

    void unlink(OrderBook book, Order o) {
        if (o.pegPrev == null) {
            book.pegHead = o.pegNext;
        } else {
            o.pegPrev.pegNext = o.pegNext;
        }
        if (o.pegNext == null) {
            book.pegTail = o.pegPrev;
        } else {
            o.pegNext.pegPrev = o.pegPrev;
        }
        o.pegPrev = null;
        o.pegNext = null;
        o.inPegList = false;
        book.pegCount--;
    }

    /** Schedules the next grid tick for the book if it has pegs and none is scheduled. */
    void ensureTick(OrderBook book, long now) {
        long throttle = book.instrument.pegThrottleNanos();
        if (!book.pegTickScheduled && book.pegCount > 0 && throttle > 0) {
            engine.schedule((Math.floorDiv(now, throttle) + 1) * throttle, MatchingEngine.TIMER_PEG, book.instrument.id());
            book.pegTickScheduled = true;
        }
    }

    void onTick(OrderBook book, long time) {
        book.pegTickScheduled = false;
        if (!book.isOpen()) {
            ensureTick(book, time); // no reprices while not open; keep the grid going
            return;
        }
        // Snapshot ids: processing one peg can fill and remove others.
        int n = 0;
        for (Order o = book.pegHead; o != null; o = o.pegNext) {
            if (n == snapshot.length) {
                snapshot = Arrays.copyOf(snapshot, n * 2);
            }
            snapshot[n++] = o.orderId;
        }
        long bid = book.referenceBid();
        long ask = book.referenceAsk();
        for (int i = 0; i < n; i++) {
            Order o = engine.order(snapshot[i]);
            if (o == null || !o.inPegList || o.queue != null) {
                continue; // gone, or waiting in a race ticket
            }
            long price = pegPrice(o.side, o.pegReference, o.discretion, bid, ask, book.instrument);
            if (price == NO_PRICE) {
                if (!o.suspended) {
                    engine.removeFromBook(o);
                    o.suspended = true;
                    engine.listener.onOrderSuspended(time, o.orderId);
                }
            } else if (o.suspended || price != o.price) {
                if (!o.suspended) {
                    engine.removeFromBook(o);
                }
                o.suspended = false;
                o.price = price;
                engine.listener.onOrderRepriced(time, o.orderId, price);
                engine.processBook(o, book, time);
            }
        }
        ensureTick(book, time);
    }
}
