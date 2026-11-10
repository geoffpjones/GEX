package clob.fix;

import clob.refdata.Instrument;

/** The gateway's view of one order: FIX identifiers, owner and running execution totals. */
final class GatewayOrder {
    final long orderId;
    final LogicalSession session;
    final Instrument instrument;
    final int account;
    final char side;
    final char ordType;
    final char tif;
    String clOrdId;
    long qty;
    long price;
    long leaves;
    long cumQty;
    double notional;
    long pendingFixing;
    boolean terminal;

    GatewayOrder(long orderId, LogicalSession session, String clOrdId, Instrument instrument, int account, char side,
            char ordType, char tif, long qty, long price) {
        this.orderId = orderId;
        this.session = session;
        this.clOrdId = clOrdId;
        this.instrument = instrument;
        this.account = account;
        this.side = side;
        this.ordType = ordType;
        this.tif = tif;
        this.qty = qty;
        this.price = price;
    }

    void fill(long qty, long price, int priceDecimals) {
        cumQty += qty;
        notional += qty * (price / Math.pow(10, priceDecimals));
    }

    double avgPx() {
        return cumQty == 0 ? 0 : notional / cumQty;
    }

    /** FIX OrdStatus from the running totals. */
    char ordStatus() {
        if (terminal && leaves == 0 && cumQty < qty) {
            return '4';
        }
        if (cumQty >= qty) {
            return '2';
        }
        return cumQty > 0 ? '1' : '0';
    }
}
