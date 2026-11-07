package clob.engine;

import clob.common.Side;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Predicate;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Fairness races (instruments with a taker or maker race interval above zero).
 *
 * <h2>Taker race</h2>
 * One per book per aggressor side. An order that would execute now (the FOK dry run finds a fill,
 * so credit and VISIBILITY rules apply, PLAN Q20 default) does not match; it joins the side's race,
 * or starts one lasting exactly the taker interval, over the half-open window [start, start + interval).
 * New orders, crossing amends and crossing peg reprices all join. Market, IOC and FOK orders join; FOK
 * is checked when applied (confirmed).
 *
 * <h2>Maker race</h2>
 * One per book per (side, price). A newly submitted order that would rest at a price with no level
 * starts one; later new orders at that (side, price) join it, even if the level has since appeared
 * (Q8 default). Pending maker-race orders are held off the book, so they are neither visible nor
 * matchable until the window closes (Q8 default). Amends and peg reprices rest normally (Q37 default).
 *
 * <h2>Lottery</h2>
 * Each account has one ticket holding its orders in arrival order. At expiry, rounds repeat until all
 * tickets are empty: shuffle the accounts that still have orders (Fisher–Yates, {@link SplitMix64}
 * seeded from the configured seed and race id), and apply each account's next order to the book as an
 * ordinary order (match, then rest or cancel by TIF). For a maker race this sets time priority.
 * Orders applied from a lottery never re-enter a race.
 */
final class RaceManager {
    static final class Ticket {
        int account;
        final OrderQueue orders = new OrderQueue();
    }

    static final class Race {
        long id;
        int instrumentId;
        boolean taker;
        Side side;
        long price;
        long expiry;
        long seed;
        final ArrayList<Ticket> tickets = new ArrayList<>();
    }

    private final MatchingEngine engine;
    private final long baseSeed;
    private final Long2ObjectHashMap<Race> active = new Long2ObjectHashMap<>();
    /** Active races in start order (deterministic iteration). */
    private final ArrayList<Race> activeList = new ArrayList<>();
    private final ArrayDeque<Race> racePool = new ArrayDeque<>();
    private final ArrayDeque<Ticket> ticketPool = new ArrayDeque<>();
    private final ArrayList<Ticket> round = new ArrayList<>();
    private final SplitMix64 rng = new SplitMix64();
    private long lastRaceId;

    RaceManager(MatchingEngine engine, long baseSeed) {
        this.engine = engine;
        this.baseSeed = baseSeed;
    }

    static boolean takerRaceRunning(OrderBook book) {
        return book.takerRaces[0] != null || book.takerRaces[1] != null;
    }

    /** Latest expiry of the book's running taker races, or {@code Long.MIN_VALUE}. */
    static long latestTakerExpiry(OrderBook book) {
        long t = Long.MIN_VALUE;
        for (Race r : book.takerRaces) {
            if (r != null) {
                t = Math.max(t, r.expiry);
            }
        }
        return t;
    }

    void joinTaker(Order o, OrderBook book, long time) {
        Race r = book.takerRaces[o.side.ordinal()];
        if (r == null) {
            r = start(book, true, o.side, 0, time + book.instrument.takerRaceNanos(), time);
            book.takerRaces[o.side.ordinal()] = r;
        }
        join(r, o, time);
    }

    /** Joins or starts a maker race for a new order about to rest. False if the order should rest normally. */
    boolean tryJoinMaker(Order o, OrderBook book, long time) {
        Race r = null;
        for (int i = 0; i < book.makerRaces.size(); i++) {
            Race m = book.makerRaces.get(i);
            if (m.side == o.side && m.price == o.price) {
                r = m;
                break;
            }
        }
        if (r == null) {
            if (book.side(o.side).find(o.price) != null) {
                return false;
            }
            r = start(book, false, o.side, o.price, time + book.instrument.makerRaceNanos(), time);
            book.makerRaces.add(r);
        }
        join(r, o, time);
        return true;
    }

    private Race start(OrderBook book, boolean taker, Side side, long price, long expiry, long time) {
        Race r = racePool.isEmpty() ? new Race() : racePool.poll();
        r.id = ++lastRaceId;
        r.instrumentId = book.instrument.id();
        r.taker = taker;
        r.side = side;
        r.price = price;
        r.expiry = expiry;
        r.seed = SplitMix64.mix(baseSeed ^ SplitMix64.mix(r.id));
        active.put(r.id, r);
        activeList.add(r);
        engine.schedule(expiry, MatchingEngine.TIMER_RACE, r.id);
        engine.listener.onRaceStarted(time, r.id, r.instrumentId, taker, side, price, expiry, r.seed);
        return r;
    }

    private void join(Race r, Order o, long time) {
        Ticket ticket = null;
        for (int i = 0; i < r.tickets.size(); i++) {
            if (r.tickets.get(i).account == o.accountId) {
                ticket = r.tickets.get(i);
                break;
            }
        }
        if (ticket == null) {
            ticket = ticketPool.isEmpty() ? new Ticket() : ticketPool.poll();
            ticket.account = o.accountId;
            r.tickets.add(ticket);
        }
        ticket.orders.add(o);
        engine.listener.onOrderJoinedRace(time, o.orderId, r.id);
    }

    void onExpiry(long raceId, long deadline) {
        Race r = active.get(raceId);
        if (r == null || r.expiry != deadline) {
            return;
        }
        active.remove(raceId);
        activeList.remove(r);
        OrderBook book = engine.book(r.instrumentId);
        if (r.taker) {
            book.takerRaces[r.side.ordinal()] = null;
        } else {
            book.makerRaces.remove(r);
        }
        rng.seed(r.seed);
        while (true) {
            round.clear();
            for (int i = 0; i < r.tickets.size(); i++) {
                if (!r.tickets.get(i).orders.isEmpty()) {
                    round.add(r.tickets.get(i));
                }
            }
            if (round.isEmpty()) {
                break;
            }
            for (int i = round.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                Ticket t = round.get(i);
                round.set(i, round.get(j));
                round.set(j, t);
            }
            for (int i = 0; i < round.size(); i++) {
                Order o = round.get(i).orders.head;
                if (o != null) {
                    round.get(i).orders.remove(o);
                    engine.applyRaceOrder(o, book, deadline);
                }
            }
        }
        for (int i = 0; i < r.tickets.size(); i++) {
            ticketPool.add(r.tickets.get(i));
        }
        r.tickets.clear();
        round.clear();
        racePool.add(r);
        if (r.taker && !takerRaceRunning(book)) {
            engine.releaseRaceHolds(book, deadline);
        }
    }

    void collect(Predicate<Order> filter, ArrayList<Order> out) {
        for (int k = 0; k < activeList.size(); k++) {
            Race r = activeList.get(k);
            for (int i = 0; i < r.tickets.size(); i++) {
                for (Order o = r.tickets.get(i).orders.head; o != null; o = o.next) {
                    if (filter.test(o)) {
                        out.add(o);
                    }
                }
            }
        }
    }
}
