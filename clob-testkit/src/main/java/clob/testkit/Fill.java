package clob.testkit;

/** A trade as seen in scenario assertions: which resting order, at what price, for how much. */
public record Fill(long passiveOrderId, long price, long qty) {
    public static Fill fill(long passiveOrderId, long price, long qty) {
        return new Fill(passiveOrderId, price, qty);
    }
}
