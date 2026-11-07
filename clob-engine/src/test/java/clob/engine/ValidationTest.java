package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationTest {
    Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY)
            .priceIncrement(5)
            .mdPriceIncrement(5)
            .minShow(2000)
            .supportedOrderTypes(EnumSet.of(OrderType.LIMIT, OrderType.ICEBERG, OrderType.PEGGED))
            .supportedTifs(EnumSet.of(TimeInForce.DAY, TimeInForce.IOC))
            .build());

    private RejectReason rejection() {
        List<Event.Rejected> r = s.events(Event.Rejected.class);
        assertEquals(1, r.size(), s.events().toString());
        return r.get(0).reason();
    }

    @Test void unknownInstrument() { s.buy(1).limit(90).qty(1000).instrument(7).send(); assertEquals(RejectReason.UNKNOWN_INSTRUMENT, rejection()); }
    @Test void userNotEntitledToAccount() { s.buy(1).limit(90).qty(1000).user(2).account(3).send(); assertEquals(RejectReason.NOT_ENTITLED, rejection()); }
    @Test void unsupportedType() { s.buy(1).market().qty(1000).send(); assertEquals(RejectReason.UNSUPPORTED_ORDER_TYPE, rejection()); }
    @Test void unsupportedTif() { s.buy(1).limit(90).qty(1000).gtc().send(); assertEquals(RejectReason.UNSUPPORTED_TIF, rejection()); }
    @Test void notYetImplementedType() { s.buy(1).type(OrderType.PEGGED).qty(1000).send(); assertEquals(RejectReason.ORDER_TYPE_NOT_IMPLEMENTED, rejection()); }
    @Test void priceOffIncrement() { s.buy(1).limit(92).qty(1000).send(); assertEquals(RejectReason.INVALID_PRICE, rejection()); }
    @Test void nonPositiveSpotPrice() { s.buy(1).limit(0).qty(1000).send(); assertEquals(RejectReason.INVALID_PRICE, rejection()); }
    @Test void qtyBelowMin() { s.buy(1).limit(90).qty(500).send(); assertEquals(RejectReason.INVALID_QUANTITY, rejection()); }
    @Test void qtyOffIncrement() { s.buy(1).limit(90).qty(1500).send(); assertEquals(RejectReason.INVALID_QUANTITY, rejection()); }
    @Test void qtyAboveMax() { s.buy(1).limit(90).qty(1_001_000).send(); assertEquals(RejectReason.INVALID_QUANTITY, rejection()); }
    @Test void icebergWithoutShow() { s.buy(1).type(OrderType.ICEBERG).price(90).qty(5000).send(); assertEquals(RejectReason.INVALID_SHOW, rejection()); }
    @Test void showBelowMinShow() { s.buy(1).iceberg(90, 1000).qty(5000).send(); assertEquals(RejectReason.INVALID_SHOW, rejection()); }
    @Test void hiddenNotAllowedWhenMinShowPositive() { s.buy(1).limit(90).qty(5000).show(0).send(); assertEquals(RejectReason.INVALID_SHOW, rejection()); }

    @Test
    void duplicateLiveOrderId() {
        s.buy(1).limit(90).qty(1000).send();
        s.buy(1).limit(90).qty(1000).send();
        assertEquals(RejectReason.DUPLICATE_ORDER_ID, rejection());
    }

    @Test
    void validOrdersAccepted() {
        s.buy(1).limit(90).qty(1000).send();
        s.buy(2).iceberg(95, 2000).qty(5000).send();
        assertEquals(List.of(new Event.Accepted(0, 1), new Event.Accepted(0, 2)), s.events());
    }
}
