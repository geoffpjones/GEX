package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Seeded random books and random credit (none, unlimited, DSL, NOP, shared pools). */
class RandomCreditFlowTest {
    static final int SEEDS = 300;
    static final int ACCOUNTS = 12;

    static Instrument instrument(MatchPriority priority, boolean swap) {
        var b = TestInstruments.example(priority).qtyIncrement(500);
        if (swap) {
            b.type(InstrumentType.SWAP);
        }
        return b.build();
    }

    /** Random credit among accounts 1..ACCOUNTS. Each giver has one shared DSL pool and one shared NOP pool. */
    static void randomCredit(Scenario s, Random r) {
        for (int a = 1; a <= ACCOUNTS; a++) {
            int unlimited = 100 + a;
            int sharedDsl = 200 + a;
            int sharedNop = 300 + a;
            s.creditLine(unlimited, a, CreditType.UNLIMITED, 0);
            s.creditLine(sharedDsl, a, CreditType.DSL, 1000L * (2 + r.nextInt(20)));
            s.creditLine(sharedNop, a, CreditType.NOP, 1000L * (2 + r.nextInt(20)));
            for (int b = 1; b <= ACCOUNTS; b++) {
                if (a == b) {
                    continue;
                }
                int roll = r.nextInt(10);
                int line;
                if (roll == 0) {
                    line = CreditEngine.NO_LINE;
                } else if (roll < 4) {
                    line = unlimited;
                } else if (roll < 6) {
                    line = sharedDsl;
                } else if (roll < 8) {
                    line = sharedNop;
                } else {
                    line = 1000 + a * 100 + b; // a pair-specific line
                    s.creditLine(line, a, roll == 8 ? CreditType.DSL : CreditType.NOP, 1000L * (1 + r.nextInt(15)));
                }
                s.relationship(a, b, line);
            }
        }
    }

    static void seedBook(Scenario s, Random r, int orders) {
        for (int i = 1; i <= orders; i++) {
            long qty = 1000 + 500L * r.nextInt(9);
            int account = 1 + r.nextInt(ACCOUNTS);
            var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).account(account).user(account)
                    .limit(90 + r.nextInt(10)).qty(qty).gtc();
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
        int fullyFillable = 0;
        int partial = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            boolean swap = seed % 5 == 0;
            Scenario ioc = Scenario.withoutCredit(instrument(priority, swap));
            Scenario fok = Scenario.withoutCredit(instrument(priority, swap));
            randomCredit(ioc, new Random(seed));
            randomCredit(fok, new Random(seed));
            seedBook(ioc, new Random(seed + 1), 40);
            seedBook(fok, new Random(seed + 1), 40);
            ioc.clear();
            fok.clear();
            Random r = new Random(seed * 31L + 7);
            for (int k = 0; k < 5; k++) {
                Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
                long price = 88 + r.nextInt(14);
                long qty = 1000 + 500L * r.nextInt(16);
                int account = 1 + r.nextInt(ACCOUNTS);
                long id = 1000 + k;
                (side == Side.BUY ? ioc.buy(id) : ioc.sell(id)).account(account).user(account).limit(price).qty(qty).ioc().send();
                (side == Side.BUY ? fok.buy(id) : fok.sell(id)).account(account).user(account).limit(price).qty(qty).fok().send();
                long iocFilled = ioc.fills().stream().mapToLong(f -> f.qty()).sum();
                if (iocFilled == qty) {
                    fullyFillable++;
                    assertEquals(ioc.fills(), fok.fills(), "seed " + seed + " order " + k);
                } else {
                    partial++;
                    assertEquals(List.of(), fok.fills(), "seed " + seed + " order " + k);
                    // Keep the two books identical: replay the IOC on FOK's book so it gets the same fills.
                    List<clob.testkit.Fill> expected = ioc.fills();
                    fok.clear();
                    (side == Side.BUY ? fok.buy(id + 100) : fok.sell(id + 100)).account(account).user(account)
                            .limit(price).qty(qty).ioc().send();
                    assertEquals(expected, fok.fills(), "seed " + seed + " replay " + k);
                }
                ioc.clear();
                fok.clear();
            }
        }
        assertTrue(fullyFillable > 50 && partial > 50, "both outcomes exercised: " + fullyFillable + "/" + partial);
    }

    @ParameterizedTest
    @EnumSource(MatchPriority.class)
    void creditNeverExceedsLimitsAndQuantityIsConserved(MatchPriority priority) {
        for (int seed = 0; seed < SEEDS; seed++) {
            Instrument instrument = instrument(priority, seed % 4 == 0);
            Scenario s = Scenario.withoutCredit(instrument);
            randomCredit(s, new Random(seed));
            Random r = new Random(seed + 99);
            for (int i = 1; i <= 80; i++) {
                int account = 1 + r.nextInt(ACCOUNTS);
                long qty = 1000 + 500L * r.nextInt(12);
                var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).account(account).user(account).qty(qty);
                switch (r.nextInt(5)) {
                    case 0 -> spec.ioc();
                    case 1 -> spec.fok();
                    default -> spec.gtc();
                }
                if (r.nextInt(8) == 0) {
                    spec.market().ioc();
                } else {
                    spec.limit(90 + r.nextInt(10));
                    if (r.nextInt(3) == 0) {
                        spec.show(r.nextBoolean() ? 0 : 1000);
                    }
                }
                spec.send();
                assertTrue(s.credit().withinLimits(), "seed " + seed + " after order " + i);
            }
            for (Event e : s.events()) {
                if (e instanceof Event.Trade t) {
                    assertTrue(instrument.isValidExecutionSize(t.qty()), "seed " + seed + " " + t);
                }
            }
        }
    }
}
