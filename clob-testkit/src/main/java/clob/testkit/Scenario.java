package clob.testkit;

import clob.common.Side;
import clob.engine.CancelSource;
import clob.engine.EngineConfig;
import clob.engine.MassCancelScope;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.OrderBook;
import clob.engine.ReplaceCommand;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test DSL over one instrument. Users 1..{@value #PARTIES} are each entitled to the account with the
 * same number. Time starts at 0 and only moves when the test says so.
 *
 * <pre>
 * Scenario s = Scenario.of(TestInstruments.visibility())
 *         .book("1|BUY|0|93|1000", "2|BUY|1000|92|1000");
 * s.sell(10).limit(92).qty(2000).ioc().send();
 * assertEquals(List.of(fill(2, 92, 1000), fill(1, 93, 1000)), s.fills());
 * </pre>
 */
public final class Scenario {
    public static final int PARTIES = 64;

    private final Instrument instrument;
    private final MatchingEngine engine;
    private final RecordingListener recorder = new RecordingListener();
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private final Map<Long, OrderSpec> sent = new HashMap<>();
    private long now;

    private Scenario(Instrument instrument, RefData refData) {
        this.instrument = instrument;
        this.engine = new MatchingEngine(refData, EngineConfig.defaults(), recorder);
    }

    public static Scenario of(Instrument instrument) {
        RefData.Builder b = RefData.builder().add(instrument);
        for (int i = 1; i <= PARTIES; i++) {
            b.entitle(i, i);
        }
        return new Scenario(instrument, b.build());
    }

    // ---------------------------------------------------------------- time

    public long now() {
        return now;
    }

    public Scenario advance(long nanos) {
        now += nanos;
        engine.onTime(now);
        return this;
    }

    public Scenario endOfDay() {
        engine.onEndOfDay(now);
        return this;
    }

    // ---------------------------------------------------------------- orders

    public OrderSpec buy(long orderId) {
        return new OrderSpec(orderId, Side.BUY);
    }

    public OrderSpec sell(long orderId) {
        return new OrderSpec(orderId, Side.SELL);
    }

    /**
     * Seeds GTC limit orders from rows of {@code id|side|show|price|qty} (the requirements' table
     * format). Each order uses account and user {@code 1 + (id - 1) % PARTIES}. Recorded events are
     * cleared afterwards.
     */
    public Scenario book(String... rows) {
        for (String row : rows) {
            String[] f = row.split("\\|");
            if (f.length != 5) {
                throw new IllegalArgumentException("Expected id|side|show|price|qty but got: " + row);
            }
            long id = Long.parseLong(f[0].trim());
            long show = Long.parseLong(f[2].trim());
            long qty = Long.parseLong(f[4].trim());
            OrderSpec spec = new OrderSpec(id, Side.valueOf(f[1].trim()))
                    .limit(Long.parseLong(f[3].trim()))
                    .qty(qty)
                    .gtc();
            if (show < qty) {
                spec.show(show);
            }
            spec.send();
        }
        recorder.clear();
        return this;
    }

    public Scenario cancel(long orderId) {
        return cancel(orderId, CancelSource.PARTICIPANT);
    }

    public Scenario cancel(long orderId, CancelSource source) {
        OrderSpec spec = sent.get(orderId);
        engine.onCancel(orderId, spec == null ? 1 : spec.user, source, now);
        return this;
    }

    public Scenario cancelAs(long orderId, int userId) {
        engine.onCancel(orderId, userId, CancelSource.PARTICIPANT, now);
        return this;
    }

    public Scenario massCancel(MassCancelScope scope, int scopeId, int userId, CancelSource source) {
        engine.onMassCancel(scope, scopeId, userId, source, now);
        return this;
    }

    public ReplaceSpec replace(long orderId) {
        OrderSpec spec = sent.get(orderId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown order " + orderId);
        }
        return new ReplaceSpec(spec);
    }

    // ---------------------------------------------------------------- inspection

    public MatchingEngine engine() {
        return engine;
    }

    public Instrument instrument() {
        return instrument;
    }

    public OrderBook book() {
        return engine.book(instrument.id());
    }

    public List<Event> events() {
        return List.copyOf(recorder.events());
    }

    public <T extends Event> List<T> events(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Event e : recorder.events()) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    /** Fills since the last {@link #clear()}, in execution order. */
    public List<Fill> fills() {
        List<Fill> out = new ArrayList<>();
        for (Event.Trade t : events(Event.Trade.class)) {
            out.add(new Fill(t.passiveOrderId(), t.price(), t.qty()));
        }
        return out;
    }

    public Scenario clear() {
        recorder.clear();
        return this;
    }

    // ---------------------------------------------------------------- builders

    /** Fluent new order. Defaults: LIMIT, DAY, fully visible, account/user derived from the id. */
    public final class OrderSpec {
        final long id;
        final Side side;
        OrderType type = OrderType.LIMIT;
        TimeInForce tif = TimeInForce.DAY;
        long price;
        long qty;
        long show = NewOrderCommand.FULLY_VISIBLE;
        long expireTime;
        int account;
        int user;
        int instrumentId = instrument.id();

        OrderSpec(long id, Side side) {
            this.id = id;
            this.side = side;
            int party = (int) (1 + Math.floorMod(id - 1, (long) PARTIES));
            this.account = party;
            this.user = party;
        }

        public OrderSpec limit(long price) { this.type = OrderType.LIMIT; this.price = price; return this; }
        public OrderSpec market() { this.type = OrderType.MARKET; this.price = 0; return this; }
        public OrderSpec iceberg(long price, long maxShow) { this.type = OrderType.ICEBERG; this.price = price; this.show = maxShow; return this; }
        public OrderSpec type(OrderType type) { this.type = type; return this; }
        public OrderSpec price(long price) { this.price = price; return this; }
        public OrderSpec qty(long qty) { this.qty = qty; return this; }
        public OrderSpec show(long show) { this.show = show; return this; }
        public OrderSpec tif(TimeInForce tif) { this.tif = tif; return this; }
        public OrderSpec day() { return tif(TimeInForce.DAY); }
        public OrderSpec gtc() { return tif(TimeInForce.GTC); }
        public OrderSpec ioc() { return tif(TimeInForce.IOC); }
        public OrderSpec fok() { return tif(TimeInForce.FOK); }
        public OrderSpec gtt(long expireTime) { this.expireTime = expireTime; return tif(TimeInForce.GTT); }
        public OrderSpec account(int account) { this.account = account; return this; }
        public OrderSpec user(int user) { this.user = user; return this; }
        public OrderSpec instrument(int instrumentId) { this.instrumentId = instrumentId; return this; }

        public Scenario send() {
            NewOrderCommand c = newOrder.reset();
            c.orderId = id;
            c.instrumentId = instrumentId;
            c.accountId = account;
            c.userId = user;
            c.side = side;
            c.type = type;
            c.tif = tif;
            c.price = price;
            c.qty = qty;
            c.maxShow = show;
            c.expireTime = expireTime;
            sent.put(id, this);
            engine.onNewOrder(c, now);
            return Scenario.this;
        }
    }

    /** Fluent cancel/replace. Unset fields default to the values last sent for the order. */
    public final class ReplaceSpec {
        private final OrderSpec order;
        private long price;
        private long qty;
        private long show;
        private int user;

        ReplaceSpec(OrderSpec order) {
            this.order = order;
            this.price = order.price;
            this.qty = order.qty;
            this.show = order.show;
            this.user = order.user;
        }

        public ReplaceSpec price(long price) { this.price = price; return this; }
        public ReplaceSpec qty(long qty) { this.qty = qty; return this; }
        public ReplaceSpec show(long show) { this.show = show; return this; }
        public ReplaceSpec user(int user) { this.user = user; return this; }

        public Scenario send() {
            ReplaceCommand c = replace.reset();
            c.orderId = order.id;
            c.userId = user;
            c.price = price;
            c.qty = qty;
            c.maxShow = show;
            engine.onReplace(c, now);
            order.price = price;
            order.qty = qty;
            order.show = show;
            return Scenario.this;
        }
    }
}
