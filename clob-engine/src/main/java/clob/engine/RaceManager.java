package clob.engine;

import clob.common.Side;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
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
 *
 * <h2>Halts and pre-open</h2>
 * When an instrument leaves OPEN, orders waiting in its races are cancelled and requests held for those
 * races are released. Orders entered during pre-open wait in one per-book lottery, whatever their side
 * or price, which is drawn when the book opens.
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
        boolean preOpen;
        final ArrayList<Ticket> tickets = new ArrayList<>();
    }

    private final MatchingEngine engine;
    private final long baseSeed;
    private final Long2ObjectHashMap<Race> active = new Long2ObjectHashMap<>(2048, 0.5f);
    /** Active races in start order (deterministic iteration). */
    private final ArrayList<Race> activeList = new ArrayList<>(1024);
    private final ArrayDeque<Race> racePool = new ArrayDeque<>(1024);
    private final ArrayDeque<Ticket> ticketPool = new ArrayDeque<>(8192);
    private final ArrayList<Ticket> round = new ArrayList<>(1024);
    private final SplitMix64 rng = new SplitMix64();
    private long lastRaceId;

    RaceManager(MatchingEngine engine, long baseSeed) {
        this.engine = engine;
        this.baseSeed = baseSeed;
        // Pre-size so race peaks don't allocate on the hot path (Phase 9).
        for (int i = 0; i < 512; i++) {
            Race r = new Race();
            r.tickets.ensureCapacity(64);
            racePool.add(r);
        }
        for (int i = 0; i < 4096; i++) {
            ticketPool.add(new Ticket());
        }
    }

    void joinTaker(Order o, OrderBook book, long time) {
        Race r = book.takerRaces[o.side.ordinal()];
        if (r == null) {
            r = start(book, true, o.side, 0, time + book.instrument.takerRaceNanos(), time);
            book.takerRaces[o.side.ordinal()] = r;
        }
        o.inTakerRace = true;
        join(r, o, time);
    }

    /** Collects a pre-open order for the book's lottery at the open. */
    void joinPreOpen(Order o, OrderBook book, long time) {
        Race r = book.preOpenRace;
        if (r == null) {
            r = start(book, false, o.side, 0, Long.MAX_VALUE, time);
            r.preOpen = true;
            book.preOpenRace = r;
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
        r.preOpen = false;
        active.put(r.id, r);
        activeList.add(r);
        if (expiry != Long.MAX_VALUE) {
            engine.schedule(expiry, MatchingEngine.TIMER_RACE, r.id);
        }
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
        engine.scheduleGtt(o); // a waiting order still expires on time and leaves its ticket
        engine.listener.onOrderJoinedRace(time, o.orderId, r.id);
    }

    void onExpiry(long raceId, long deadline) {
        Race r = active.get(raceId);
        if (r != null && r.expiry == deadline) {
            resolve(r, deadline);
        }
    }

    /** Draws the book's pre-open lottery. */
    void resolvePreOpen(OrderBook book, long time) {
        if (book.preOpenRace != null) {
            resolve(book.preOpenRace, time);
        }
    }

    /** The book left OPEN: cancels every order waiting in its races and frees requests held for them. */
    void abortAll(OrderBook book, long time) {
        for (int k = 0; k < activeList.size(); ) {
            Race r = activeList.get(k);
            if (r.instrumentId != book.instrument.id()) {
                k++;
                continue;
            }
            detach(r, book);
            for (int i = 0; i < r.tickets.size(); i++) {
                OrderQueue orders = r.tickets.get(i).orders;
                while (orders.head != null) {
                    Order o = orders.head;
                    o.inTakerRace = false;
                    engine.cancelResting(o, CancelReason.HALTED, time);
                }
            }
            recycle(r);
            if (r.taker) {
                engine.releaseRaceHolds(book, r.id, time);
            }
        }
    }

    private void detach(Race r, OrderBook book) {
        active.remove(r.id);
        activeList.remove(r);
        if (r.preOpen) {
            book.preOpenRace = null;
        } else if (r.taker) {
            book.takerRaces[r.side.ordinal()] = null;
        } else {
            book.makerRaces.remove(r);
        }
    }

    private void recycle(Race r) {
        for (int i = 0; i < r.tickets.size(); i++) {
            ticketPool.add(r.tickets.get(i));
        }
        r.tickets.clear();
        racePool.add(r);
    }

    private void resolve(Race r, long deadline) {
        long raceId = r.id;
        boolean taker = r.taker;
        OrderBook book = engine.book(r.instrumentId);
        detach(r, book);
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
                    o.inTakerRace = false;
                    engine.applyRaceOrder(o, book, deadline);
                }
            }
        }
        round.clear();
        recycle(r);
        if (taker) {
            engine.releaseRaceHolds(book, raceId, deadline);
        }
    }

    interface OrderWriter {
        void write(Order o, long raceId, int account) throws IOException;
    }

    void saveMeta(DataOutput out) throws IOException {
        out.writeLong(lastRaceId);
        out.writeInt(activeList.size());
        for (Race r : activeList) {
            out.writeLong(r.id);
            out.writeInt(r.instrumentId);
            out.writeBoolean(r.taker);
            out.writeUTF(r.side.name());
            out.writeLong(r.price);
            out.writeLong(r.expiry);
            out.writeLong(r.seed);
            out.writeBoolean(r.preOpen);
            out.writeInt(r.tickets.size());
            for (Ticket t : r.tickets) {
                out.writeInt(t.account);
            }
        }
    }

    void forEachWaiting(OrderWriter writer) throws IOException {
        for (Race r : activeList) {
            for (Ticket t : r.tickets) {
                for (Order o = t.orders.head; o != null; o = o.next) {
                    writer.write(o, r.id, t.account);
                }
            }
        }
    }

    void restoreMeta(DataInput in) throws IOException {
        lastRaceId = in.readLong();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            Race r = racePool.isEmpty() ? new Race() : racePool.poll();
            r.id = in.readLong();
            r.instrumentId = in.readInt();
            r.taker = in.readBoolean();
            r.side = Side.valueOf(in.readUTF());
            r.price = in.readLong();
            r.expiry = in.readLong();
            r.seed = in.readLong();
            r.preOpen = in.readBoolean();
            int tickets = in.readInt();
            for (int k = 0; k < tickets; k++) {
                Ticket t = ticketPool.isEmpty() ? new Ticket() : ticketPool.poll();
                t.account = in.readInt();
                r.tickets.add(t);
            }
            active.put(r.id, r);
            activeList.add(r);
            OrderBook book = engine.book(r.instrumentId);
            if (r.preOpen) {
                book.preOpenRace = r;
            } else if (r.taker) {
                book.takerRaces[r.side.ordinal()] = r;
            } else {
                book.makerRaces.add(r);
            }
        }
    }

    void restoreWaiting(Order o, long raceId, int account) {
        Race r = active.get(raceId);
        for (int i = 0; i < r.tickets.size(); i++) {
            if (r.tickets.get(i).account == account) {
                r.tickets.get(i).orders.add(o);
                return;
            }
        }
        throw new IllegalStateException("Snapshot names ticket " + account + " missing from race " + raceId);
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
