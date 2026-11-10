package clob.marketdata;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.EngineConfig;
import clob.engine.NewOrderCommand;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.InputHandler;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/** Feeds stamped inputs to a market data service (and optionally other handlers) and records publishes. */
final class MdHarness {
    static final long MS = 1_000_000L;

    record Published(long subscriptionId, long boundary, String view) {}

    final RefData refData;
    final MarketDataService md;
    final List<InputHandler> others = new ArrayList<>();
    final List<Published> published = new ArrayList<>();
    private final List<byte[]> history = new ArrayList<>();
    final MarketDataSink sink = (id, boundary, view) -> published.add(new Published(id, boundary, view.toString()));
    private final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
    private final NewOrderCommand c = new NewOrderCommand();
    long seq;
    long time = 1_000 * MS;

    MdHarness(Instrument instrument) {
        RefData.Builder b = RefData.builder().add(instrument);
        for (int a = 1; a <= 8; a++) {
            b.entitle(a, a);
        }
        refData = b.build();
        md = new MarketDataService(refData, EngineConfig.defaults(), 8);
        feed(InputCodec.usdRate(buf, 0, instrument.baseCurrency(), CreditEngine.RATE_SCALE));
        feed(InputCodec.valueDates(buf, 0, instrument.id(), 20_000, -1));
        for (int a = 1; a <= 8; a++) {
            feed(InputCodec.creditLine(buf, 0, a, a, CreditType.UNLIMITED, 0));
        }
    }

    static Instrument.Builder fourDp() {
        // "4 dp, 1 pip increment for orders, 5 pip increment for market data"
        return Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(4).priceIncrement(1).mdPriceIncrement(5)
                .minQty(1000).qtyIncrement(1000).maxQty(1_000_000).minTtlNanos(0).mdLevels(5).mdIntervalNanos(0);
    }

    void feed(int length) {
        InputCodec.stamp(buf, 0, ++seq, ++time);
        byte[] copy = new byte[length];
        buf.getBytes(0, copy);
        history.add(copy);
        md.onInput(buf, 0, length);
        for (InputHandler h : others) {
            h.onInput(buf, 0, length);
        }
    }

    /** A second engine that has seen every input so far and follows from now on (like the matching engine). */
    clob.engine.MatchingEngine follower() {
        clob.engine.MatchingEngine e = new clob.engine.MatchingEngine(refData, EngineConfig.defaults(),
                new CreditEngine(8, refData), clob.engine.NullEngineListener.INSTANCE);
        clob.sequencer.EngineDispatcher d = new clob.sequencer.EngineDispatcher(e, (s, t, ty, r) -> { });
        UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        for (byte[] m : history) {
            b.putBytes(0, m);
            d.onInput(b, 0, m.length);
        }
        others.add(d);
        return e;
    }

    /** Both directions of credit between two accounts (on their unlimited lines). */
    MdHarness trade(int a, int b) {
        feed(InputCodec.creditRelationship(buf, 0, a, b, CreditEngine.ALL_INSTRUMENTS, a));
        feed(InputCodec.creditRelationship(buf, 0, b, a, CreditEngine.ALL_INSTRUMENTS, b));
        return this;
    }

    /** A new line from {@code giver} and its relationship to {@code to}. */
    MdHarness line(int lineId, int giver, CreditType type, long limitUsd, int to) {
        feed(InputCodec.creditLine(buf, 0, lineId, giver, type, limitUsd));
        feed(InputCodec.creditRelationship(buf, 0, giver, to, CreditEngine.ALL_INSTRUMENTS, lineId));
        return this;
    }

    MdHarness order(long id, int account, Side side, long price, long qty, long show) {
        c.reset();
        c.orderId = id;
        c.instrumentId = 0;
        c.accountId = account;
        c.userId = account;
        c.side = side;
        c.type = OrderType.LIMIT;
        c.tif = TimeInForce.GTC;
        c.price = price;
        c.qty = qty;
        c.maxShow = show;
        feed(InputCodec.newOrder(buf, 0, c));
        return this;
    }

    MdHarness order(long id, int account, Side side, long price, long qty) {
        return order(id, account, side, price, qty, NewOrderCommand.FULLY_VISIBLE);
    }

    MdHarness cancel(long id, int account) {
        feed(InputCodec.cancel(buf, 0, id, account, CancelSource.PARTICIPANT));
        return this;
    }

    MdHarness tick(long boundary) {
        feed(InputCodec.mdTick(buf, 0, boundary));
        return this;
    }

    List<String> views(long subscriptionId) {
        return published.stream().filter(p -> p.subscriptionId() == subscriptionId).map(Published::view).toList();
    }
}
