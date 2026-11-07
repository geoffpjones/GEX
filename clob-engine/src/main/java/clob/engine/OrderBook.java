package clob.engine;

import clob.common.Side;
import clob.refdata.Instrument;
import java.util.ArrayList;
import java.util.List;

/** One instrument's book. The public methods are a read-only view for tests and diagnostics. */
public final class OrderBook {
    final Instrument instrument;
    final BookSide bids;
    final BookSide asks;

    /** Peg orders (resting or suspended), in arrival order. */
    Order pegHead;
    Order pegTail;
    int pegCount;
    boolean pegTickScheduled;

    /** Mid book: matches only mid orders, in time priority. */
    final OrderQueue midBuys = new OrderQueue();
    final OrderQueue midSells = new OrderQueue();
    long lastMidBid = Long.MIN_VALUE;
    long lastMidAsk = Long.MIN_VALUE;

    /** Running taker race per aggressor side (indexed by {@code Side.ordinal()}). */
    final RaceManager.Race[] takerRaces = new RaceManager.Race[2];
    final java.util.ArrayList<RaceManager.Race> makerRaces = new java.util.ArrayList<>();
    /** Orders whose queued request waits for the taker races to finish. */
    long[] heldIds = new long[16];
    int heldCount;

    public boolean takerRaceRunning(Side side) {
        return takerRaces[side.ordinal()] != null;
    }

    public int makerRaceCount() {
        return makerRaces.size();
    }

    OrderBook(Instrument instrument, int initialLevels) {
        this.instrument = instrument;
        this.bids = new BookSide(Side.BUY, initialLevels);
        this.asks = new BookSide(Side.SELL, initialLevels);
    }

    BookSide side(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    public Instrument instrument() {
        return instrument;
    }

    public int levelCount(Side side) {
        return side(side).size();
    }

    /** Price of the {@code index}-th best level (hidden-only levels included). */
    public long priceAt(Side side, int index) {
        return side(side).levelAt(index).price;
    }

    public long visibleQtyAt(Side side, long price) {
        PriceLevel l = side(side).find(price);
        return l == null ? 0 : l.visibleQty;
    }

    public long hiddenQtyAt(Side side, long price) {
        PriceLevel l = side(side).find(price);
        return l == null ? 0 : l.hiddenQty;
    }

    /** Order ids at a price in priority order: displayed queue, then hidden queue. Allocates. */
    public List<Long> orderIdsAt(Side side, long price) {
        List<Long> ids = new ArrayList<>();
        PriceLevel l = side(side).find(price);
        if (l != null) {
            for (Order o = l.visHead; o != null; o = o.next) {
                ids.add(o.orderId);
            }
            for (Order o = l.hidHead; o != null; o = o.next) {
                ids.add(o.orderId);
            }
        }
        return ids;
    }

    public boolean isEmpty() {
        return bids.size() == 0 && asks.size() == 0;
    }

    /** Reference bid for pegs and mid orders: best displayed non-peg bid, or {@code Long.MIN_VALUE}. */
    public long referenceBid() {
        return bids.bestNonPegVisible();
    }

    /** Reference offer for pegs and mid orders: best displayed non-peg offer, or {@code Long.MIN_VALUE}. */
    public long referenceAsk() {
        return asks.bestNonPegVisible();
    }

    /** Mid order ids in time priority. Allocates. */
    public List<Long> midOrderIds(Side side) {
        List<Long> ids = new ArrayList<>();
        for (Order o = (side == Side.BUY ? midBuys : midSells).head; o != null; o = o.next) {
            ids.add(o.orderId);
        }
        return ids;
    }

    public int pegCount() {
        return pegCount;
    }
}
