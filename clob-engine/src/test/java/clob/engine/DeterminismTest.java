package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The same sequenced input must give the same output, event for event. */
class DeterminismTest {

    static List<Event> run(long seed) {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY)
                .qtyIncrement(500).minTtlNanos(10).build());
        Random r = new Random(seed);
        for (int i = 1; i <= 2_000; i++) {
            switch (r.nextInt(12)) {
                case 0, 1 -> s.cancel(1 + r.nextInt(i));
                case 2 -> s.advance(r.nextInt(8));
                case 3 -> {
                    if (s.engine().leavesOf(i - 1) > 0) {
                        s.replace(i - 1).price(90 + r.nextInt(10)).send();
                    }
                }
                default -> {
                    var spec = (r.nextBoolean() ? s.buy(i) : s.sell(i)).limit(90 + r.nextInt(10))
                            .qty(1000 + 500L * r.nextInt(6));
                    switch (r.nextInt(4)) {
                        case 0 -> spec.ioc();
                        case 1 -> spec.show(0).gtc();
                        case 2 -> spec.gtt(s.now() + 1 + r.nextInt(30));
                        default -> spec.day();
                    }
                    spec.send();
                }
            }
        }
        s.endOfDay();
        return s.events();
    }

    @Test
    void replayIsIdentical() {
        for (long seed = 0; seed < 20; seed++) {
            assertEquals(run(seed), run(seed), "seed " + seed);
        }
    }
}
