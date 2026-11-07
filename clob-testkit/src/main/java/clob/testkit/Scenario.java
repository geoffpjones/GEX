package clob.testkit;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
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
 * <p>Credit: {@link #of} gives every account an UNLIMITED line (id = account number) to every
 * <em>other</em> account, so any two different accounts can trade and an account cannot trade with
 * itself. {@link #withoutCredit} starts with no relationships. Either way the instrument's base
 * currency has a USD rate of 1 and its value date is {@link #SPOT_DATE} (far date
 * {@link #FAR_DATE} for a SWAP).
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
    public static final int SPOT_DATE = 20_000;
    public static final int FAR_DATE = 20_030;
    /** Line ids handed out by {@link #bilateral} start here, above the per-account unlimited lines. */
    private int nextLineId = 1_000;

    private final Instrument instrument;
    private final MatchingEngine engine;
    private final RecordingListener recorder = new RecordingListener();
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private final Map<Long, OrderSpec> sent = new HashMap<>();
    private long now;

    private Scenario(Instrument instrument, RefData refData) {
        this.instrument = instrument;
        this.engine = new MatchingEngine(refData, EngineConfig.defaults(), new CreditEngine(PARTIES, refData),
                recorder);
        engine.onUsdRate(instrument.baseCurrency(), CreditEngine.RATE_SCALE, 0);
        engine.onValueDates(instrument.id(), SPOT_DATE, FAR_DATE, 0);
    }

    private static Scenario create(Instrument instrument) {
        RefData.Builder b = RefData.builder().add(instrument);
        for (int i = 1; i <= PARTIES; i++) {
            b.entitle(i, i);
        }
        return new Scenario(instrument, b.build());
    }

    /** Every pair of different accounts can trade, without limit. */
    public static Scenario of(Instrument instrument) {
        Scenario s = create(instrument);
        for (int a = 1; a <= PARTIES; a++) {
            s.engine.onCreditLine(a, a, CreditType.UNLIMITED, 0, 0);
            for (int b = 1; b <= PARTIES; b++) {
                if (a != b) {
                    s.engine.onCreditRelationship(a, b, CreditEngine.ALL_INSTRUMENTS, a, 0);
                }
            }
        }
        return s;
    }

    /** No credit relationships: nothing can trade until the test adds some. */
    public static Scenario withoutCredit(Instrument instrument) {
        return create(instrument);
    }

    // ---------------------------------------------------------------- credit

    public Scenario creditLine(int lineId, int giver, CreditType type, long limitUsd) {
        engine.onCreditLine(lineId, giver, type, limitUsd, now);
        return this;
    }

    /** Credit {@code from} extends to {@code to} for every instrument without an override. */
    public Scenario relationship(int from, int to, int lineId) {
        engine.onCreditRelationship(from, to, CreditEngine.ALL_INSTRUMENTS, lineId, now);
        return this;
    }

    public Scenario relationship(int from, int to, int instrumentId, int lineId) {
        engine.onCreditRelationship(from, to, instrumentId, lineId, now);
        return this;
    }

    /** Gives each of {@code a} and {@code b} a new line of the given type to the other. Returns the lines {a→b, b→a}. */
    public int[] bilateral(int a, int b, CreditType type, long limitUsd) {
        int ab = nextLineId++;
        int ba = nextLineId++;
        creditLine(ab, a, type, limitUsd).relationship(a, b, ab);
        creditLine(ba, b, type, limitUsd).relationship(b, a, ba);
        return new int[] {ab, ba};
    }

    public Scenario usdRate(String currency, long rateE8) {
        engine.onUsdRate(currency, rateE8, now);
        return this;
    }

    public Scenario valueDates(int near, int far) {
        engine.onValueDates(instrument.id(), near, far, now);
        return this;
    }

    public Scenario rollValueDate(int today) {
        engine.onValueDateRoll(today, now);
        return this;
    }

    public CreditEngine credit() {
        return engine.credit();
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
