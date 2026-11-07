package clob.engine;

/** Mutable, reusable cancel/replace input. {@code qty} is the new total order quantity. */
public final class ReplaceCommand {
    public long orderId;
    public int userId;
    public long price;
    public long qty;
    public long maxShow = NewOrderCommand.FULLY_VISIBLE;

    public ReplaceCommand reset() {
        orderId = 0;
        userId = 0;
        price = 0;
        qty = 0;
        maxShow = NewOrderCommand.FULLY_VISIBLE;
        return this;
    }
}
