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
}
