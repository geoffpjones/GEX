package clob.engine;

/**
 * Mutable, reusable cancel/replace input. {@code qty} is the new total order quantity. {@code price}
 * applies to LIMIT and ICEBERG orders; {@code discretion} to PEGGED (from the next peg tick) and MID.
 */
public final class ReplaceCommand {
    public long orderId;
    public int userId;
    public long price;
    public long qty;
    public long maxShow = NewOrderCommand.FULLY_VISIBLE;
    public long discretion;

    public ReplaceCommand reset() {
        orderId = 0;
        userId = 0;
        price = 0;
        qty = 0;
        maxShow = NewOrderCommand.FULLY_VISIBLE;
        discretion = 0;
        return this;
    }
}
