package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.PegReference;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Seeded random flow over every order type, with time crossing peg ticks and a fixing cut-off. */
class RandomAllTypesFlowTest {
    static final long MS = 1_000_000L;
    static final int SEEDS = 200;

    static Instrument instrument(MatchPriority priority) {
        return TestInstruments.example(priority).qtyIncrement(500).priceIncrement(1).build();
    }

    record Run(Scenario scenario, Map<Long, Long> qtys) {}

    static Run run(Instrument instrument, long seed) {
        Map<Long, Long> qtys = new HashMap<>();
        Scenario s = Scenario.withoutCredit(instrument);
        RandomCreditFlowTest.randomCredit(s, new Random(seed));
        s.fixingSchedule(400 * MS, 500 * MS);
        Random r = new Random(seed + 17);
        for (int i = 1; i <= 150; i++) {
            int account = 1 + r.nextInt(RandomCreditFlowTest.ACCOUNTS);
            long qty = 1000 + 500L * r.nextInt(8);
            var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).account(account).user(account).qty(qty);
            int kind = r.nextInt(20);
            if (kind < 9) {
                spec.limit(90 + r.nextInt(10));
                if (r.nextInt(4) == 0) {
                    spec.show(r.nextBoolean() ? 0 : 1000);
                }
            } else if (kind < 12) {
                spec.peg(r.nextBoolean() ? PegReference.MID : PegReference.NEAR_TOUCH, r.nextInt(5) - 2);
            } else if (kind < 15) {
                spec.mid(r.nextInt(5) - 2);
            } else if (kind < 17) {
                spec.fixing();
            } else if (kind < 18) {
                spec.market();
            } else {
                spec.limit(90 + r.nextInt(10)).ioc();
            }
            if (kind < 15 && r.nextInt(6) == 0) {
                spec.fok();
            } else if (kind < 17) {
                spec.tif(r.nextBoolean() ? clob.refdata.TimeInForce.DAY : clob.refdata.TimeInForce.GTC);
            } else {
                spec.ioc();
            }
            spec.send();
            qtys.put((long) i, qty);
            switch (r.nextInt(6)) {
                case 0 -> s.cancel(1 + r.nextInt(i));
                case 1 -> s.advance(1 + r.nextInt(15) * MS);
                default -> { }
            }
        }
        s.advance(600 * MS);
        s.fixingRate(95);
        s.endOfDay();
        assertTrue(s.credit().withinLimits(), "seed " + seed);
        return new Run(s, qtys);
    }

    @ParameterizedTest
    @EnumSource(MatchPriority.class)
    void invariantsHold(MatchPriority priority) {
        Instrument instrument = instrument(priority);
        int trades = 0;
        int reprices = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Run run = run(instrument, seed);
            List<Event> events = run.scenario().events();
            Map<Long, Long> accepted = new HashMap<>();
            Map<Long, Long> done = new HashMap<>();
            for (Event e : events) {
                switch (e) {
                    case Event.Trade t when t.aggressorLeaves() >= 0 -> {
                        trades++;
                        assertTrue(instrument.isValidExecutionSize(t.qty()), "seed " + seed + " " + t);
                        assertEquals(0, Math.floorMod(t.price(), instrument.priceIncrement()), "seed " + seed + " " + t);
                        done.merge(t.aggressorOrderId(), t.qty(), Long::sum);
                        done.merge(t.passiveOrderId(), t.qty(), Long::sum);
                    }
                    case Event.FixingMatched m -> {
                        assertTrue(instrument.isValidExecutionSize(m.qty()), "seed " + seed + " " + m);
                        done.merge(m.aggressorOrderId(), m.qty(), Long::sum);
                        done.merge(m.passiveOrderId(), m.qty(), Long::sum);
                    }
                    case Event.Cancelled c -> done.merge(c.orderId(), c.qty(), Long::sum);
                    case Event.Accepted a -> accepted.put(a.orderId(), 0L);
                    case Event.Repriced p -> reprices++;
                    default -> { }
                }
            }
            for (Long id : accepted.keySet()) {
                long leaves = Math.max(0, run.scenario().engine().leavesOf(id));
                assertEquals(run.qtys().get(id), done.getOrDefault(id, 0L) + leaves,
                        "quantity conserved, seed " + seed + " order " + id);
            }
            assertEquals(events, run(instrument, seed).scenario().events(), "replay must be identical, seed " + seed);
        }
        assertTrue(trades > 1000 && reprices > 100, "flow exercised: trades=" + trades + " reprices=" + reprices);
    }
}
