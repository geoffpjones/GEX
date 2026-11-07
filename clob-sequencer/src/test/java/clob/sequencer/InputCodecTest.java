package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditType;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.testkit.Event;
import clob.testkit.RecordingListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class InputCodecTest {
    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));

    @Test
    void newOrderRoundTrips() {
        NewOrderCommand c = new NewOrderCommand();
        c.orderId = 123456789012L;
        c.instrumentId = 7;
        c.accountId = 3;
        c.userId = 4;
        c.side = Side.SELL;
        c.type = OrderType.PEGGED;
        c.tif = TimeInForce.GTT;
        c.pegReference = PegReference.NEAR_TOUCH;
        c.price = -55;
        c.qty = 5_000_000;
        c.maxShow = 1_000_000;
        c.expireTime = Long.MAX_VALUE - 1;
        c.discretion = -2;
        c.fixingEventId = 9;
        int length = InputCodec.newOrder(buf, 0, c);
        InputCodec.stamp(buf, 0, 42, 99);
        assertEquals(length, InputCodec.length(buf, 0));
        assertEquals(InputCodec.NEW_ORDER, InputCodec.type(buf, 0));
        assertEquals(42, InputCodec.sequence(buf, 0));
        assertEquals(99, InputCodec.time(buf, 0));
        NewOrderCommand d = new NewOrderCommand();
        InputCodec.decodeNewOrder(buf, 0, d);
        assertEquals(List.of(c.orderId, (long) c.instrumentId, (long) c.accountId, (long) c.userId, c.price, c.qty,
                c.maxShow, c.expireTime, c.discretion, (long) c.fixingEventId),
                List.of(d.orderId, (long) d.instrumentId, (long) d.accountId, (long) d.userId, d.price, d.qty,
                        d.maxShow, d.expireTime, d.discretion, (long) d.fixingEventId));
        assertEquals(List.of(c.side, c.type, c.tif, c.pegReference), List.of(d.side, d.type, d.tif, d.pegReference));
    }

    @Test
    void nullEnumsRoundTrip() {
        NewOrderCommand c = new NewOrderCommand();
        InputCodec.newOrder(buf, 0, c);
        NewOrderCommand d = new NewOrderCommand();
        InputCodec.decodeNewOrder(buf, 0, d);
        assertEquals(null, d.side);
        assertEquals(null, d.pegReference);
    }

    @Test
    void replaceRoundTrips() {
        ReplaceCommand c = new ReplaceCommand();
        c.orderId = 5;
        c.userId = 6;
        c.price = 7;
        c.qty = 8;
        c.maxShow = 9;
        c.discretion = -10;
        InputCodec.replace(buf, 0, c);
        ReplaceCommand d = new ReplaceCommand();
        InputCodec.decodeReplace(buf, 0, d);
        assertEquals(List.of(5L, 6L, 7L, 8L, 9L, -10L), List.of(d.orderId, (long) d.userId, d.price, d.qty, d.maxShow, d.discretion));
    }

    @Test
    void currencyCodeRoundTrips() {
        InputCodec.usdRate(buf, 0, "EUR", 110_000_000L);
        assertEquals("EUR", InputCodec.usdRateCurrency(buf, 0));
    }

    @Test
    void everyAdminInputDrivesTheEngineThroughTheDispatcher() {
        RefData refData = Fixture.refData();
        RecordingListener rec = new RecordingListener();
        List<String> refused = new ArrayList<>();
        EngineDispatcher d = new EngineDispatcher(Fixture.engine(refData, rec),
                (seq, time, type, reason) -> refused.add(type + ":" + reason));
        long t = 1;
        for (byte[] msg : new Fixture().admin(1).inputs) {
            UnsafeBuffer b = Fixture.wrap(msg);
            InputCodec.stamp(b, 0, t, t++);
            d.onInput(b, 0, msg.length);
        }
        // Make 1 and 2 able to trade on instrument 1 without limit, then trade.
        dispatch(d, InputCodec.creditLine(buf, 0, 900, 1, CreditType.UNLIMITED, 0), t++);
        dispatch(d, InputCodec.creditRelationship(buf, 0, 1, 2, 1, 900), t++);
        dispatch(d, InputCodec.creditLine(buf, 0, 901, 2, CreditType.UNLIMITED, 0), t++);
        dispatch(d, InputCodec.creditRelationship(buf, 0, 2, 1, 1, 901), t++);
        NewOrderCommand c = new NewOrderCommand();
        c.orderId = 1; c.instrumentId = 1; c.accountId = 1; c.userId = 1; c.side = Side.BUY; c.type = OrderType.LIMIT;
        c.tif = TimeInForce.DAY; c.price = 95; c.qty = 1000;
        dispatch(d, InputCodec.newOrder(buf, 0, c), t++);
        c.orderId = 2; c.accountId = 2; c.userId = 2; c.side = Side.SELL;
        dispatch(d, InputCodec.newOrder(buf, 0, c), t++);
        assertEquals(1, rec.events().stream().filter(e -> e instanceof Event.Trade).count());
        assertTrue(refused.isEmpty(), refused.toString());

        dispatch(d, InputCodec.creditLine(buf, 0, 902, 1, CreditType.DSL, -5), t++); // invalid limit
        dispatch(d, InputCodec.fixingRate(buf, 0, 0, 0, 100), t++);                   // nothing awaits a rate
        assertEquals(2, refused.size(), refused.toString());
    }

    private void dispatch(EngineDispatcher d, int length, long t) {
        InputCodec.stamp(buf, 0, t, t);
        d.onInput(buf, 0, length);
    }
}
