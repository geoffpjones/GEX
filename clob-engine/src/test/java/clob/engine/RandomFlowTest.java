package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.TimeInForce;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Seeded random order flow, checking invariants that hold whatever the matching rules are.
 * (A jqwik migration can come later; plain seeded loops keep failures reproducible.)
 */
class RandomFlowTest {
    static final int SEEDS = 300;

    static Instrument instrument(MatchPriority priority) {
        return TestInstruments.example(priority).qtyIncrement(500).build();
    }

    /** Applies the same random seeding orders to a scenario. */
    static void seedBook(Scenario s, Random r, int orders) {
        for (int i = 1; i <= orders; i++) {
            long qty = 1000 + 500L * r.nextInt(9);
            var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).limit(90 + r.nextInt(10)).qty(qty).gtc();
            switch (r.nextInt(4)) {
                case 0 -> spec.show(0);
                case 1 -> spec.show(Math.min(qty, 1000 + 500L * r.nextInt(3)));
                default -> { }
            }
            spec.send();
        }
    }

    @ParameterizedTest
    @EnumSource(MatchPriority.class)
    void fokFillsExactlyWhenIocWouldFillCompletely(MatchPriority priority) {
        for (int seed = 0; seed < SEEDS; seed++) {
            Scenario ioc = Scenario.of(instrument(priority));
            Scenario fok = Scenario.of(instrument(priority));
            seedBook(ioc, new Random(seed), 30);
            seedBook(fok, new Random(seed), 30);
            ioc.clear();
            fok.clear();
            Random r = new Random(seed * 31L + 7);
            Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 88 + r.nextInt(14);
            long qty = 1000 + 500L * r.nextInt(30);
            (side == Side.BUY ? ioc.buy(1000) : ioc.sell(1000)).limit(price).qty(qty).ioc().send();
            (side == Side.BUY ? fok.buy(1000) : fok.sell(1000)).limit(price).qty(qty).fok().send();

            long iocFilled = ioc.fills().stream().mapToLong(f -> f.qty()).sum();
            boolean fullyFillable = iocFilled == qty;
            if (fullyFillable) {
                assertEquals(ioc.fills(), fok.fills(), "seed " + seed);
            } else {
                assertEquals(List.of(), fok.fills(), "seed " + seed);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MatchPriority.class)
    void quantityIsConservedAndFillsAreValidSizes(MatchPriority priority) {
        Instrument instrument = instrument(priority);
        for (int seed = 0; seed < SEEDS; seed++) {
            Scenario s = Scenario.of(instrument);
            Random r = new Random(seed);
            Map<Long, Long> accepted = new HashMap<>();
            Map<Long, Long> done = new HashMap<>();
            for (int i = 1; i <= 60; i++) {
                int action = r.nextInt(10);
                if (action < 7 || i < 5) {
                    long qty = 1000 + 500L * r.nextInt(9);
                    TimeInForce tif = TimeInForce.values()[r.nextInt(TimeInForce.values().length)];
                    var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).qty(qty).tif(tif);
                    if (tif == TimeInForce.GTT) {
                        spec.gtt(s.now() + 1 + r.nextInt(50));
                    }
                    if (r.nextInt(10) == 0) {
                        spec.market();
                    } else {
                        spec.limit(90 + r.nextInt(10));
                        if (r.nextInt(3) == 0) {
                            spec.show(r.nextBoolean() ? 0 : 1000);
                        }
                    }
                    spec.send();
                    accepted.put((long) i, qty);
                } else if (action < 9) {
                    s.cancel(1 + r.nextInt(i - 1));
                } else {
                    s.advance(1 + r.nextInt(20));
                }
            }
            s.endOfDay();
            for (Event e : s.events()) {
                if (e instanceof Event.Trade t) {
                    assertTrue(instrument.isValidExecutionSize(t.qty()), "seed " + seed + " " + t);
                    done.merge(t.aggressorOrderId(), t.qty(), Long::sum);
                    done.merge(t.passiveOrderId(), t.qty(), Long::sum);
                } else if (e instanceof Event.Cancelled c) {
                    done.merge(c.orderId(), c.qty(), Long::sum);
                }
            }
            for (var entry : accepted.entrySet()) {
                long id = entry.getKey();
                if (!acceptedIds(s).contains(id)) {
                    continue; // rejected at entry
                }
                long leaves = Math.max(0, s.engine().leavesOf(id));
                assertEquals(entry.getValue(), done.getOrDefault(id, 0L) + leaves, "seed " + seed + " order " + id);
            }
        }
    }

    private static java.util.Set<Long> acceptedIds(Scenario s) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Event.Accepted a : s.events(Event.Accepted.class)) {
            ids.add(a.orderId());
        }
        return ids;
    }
}
