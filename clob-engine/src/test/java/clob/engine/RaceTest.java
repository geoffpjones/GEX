package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.PegReference;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fairness races. Race interval 5ms. */
class RaceTest {
    static final long MS = 1_000_000L;
    static final long RACE = 5 * MS;

    static Instrument taker(boolean queueCancels) {
        return TestInstruments.example(MatchPriority.VISIBILITY).takerRaceNanos(RACE).queueCancels(queueCancels).build();
    }

    static Instrument maker() {
        return TestInstruments.example(MatchPriority.VISIBILITY).makerRaceNanos(RACE).build();
    }

    static List<Long> aggressors(Scenario s) {
        return s.events(Event.Trade.class).stream().map(Event.Trade::aggressorOrderId).toList();
    }

    @Nested
    class Taker {
        /** requirements.md: "given a market with a taker-race interval of 5ms: 1|BUY|1000|92". */
        Scenario s = Scenario.of(taker(false)).book("1|BUY|1000|92|1000");

        @Test
        void crossingOrdersJoinTheRaceAndNothingTradesUntilItExpires() {
            s.sell(10).limit(92).qty(1000).day().send();
            s.advance(MS).sell(11).limit(91).qty(1000).day().send();
            assertEquals(2, s.events(Event.JoinedRace.class).size());
            assertEquals(1, s.events(Event.RaceStarted.class).size(), "one race per side");
            assertEquals(RACE, s.events(Event.RaceStarted.class).get(0).expiry(), "precisely 5ms from the first order");
            assertEquals(List.of(), s.fills());
            s.advance(RACE - MS);
            assertEquals(1, s.fills().size());
            long loser = aggressors(s).get(0) == 10 ? 11 : 10;
            assertTrue(s.book().orderIdsAt(Side.SELL, loser == 10 ? 92 : 91).contains(loser), "loser rests per its TIF");
        }

        @Test
        void lotteryIsFairBetweenAnEarlyAndALateAccount() {
            int earlyWins = 0;
            int runs = 2000;
            for (long seed = 0; seed < runs; seed++) {
                Scenario t = Scenario.of(taker(false), seed).book("1|BUY|1000|92|1000");
                t.sell(10).limit(92).qty(1000).ioc().send();
                t.advance(4 * MS).sell(11).limit(92).qty(1000).ioc().send(); // 4ms later, still inside the race
                t.advance(MS);
                if (aggressors(t).get(0) == 10) {
                    earlyWins++;
                }
            }
            assertTrue(earlyWins > runs * 0.45 && earlyWins < runs * 0.55, "early account won " + earlyWins + "/" + runs);
        }

        @Test
        void oneTicketPerAccountAndEachAccountGetsOneOrderPerRound() {
            Scenario t = Scenario.of(taker(false)).book("1|BUY|1000|92|1000", "2|BUY|1000|92|1000", "3|BUY|1000|92|1000",
                    "4|BUY|1000|92|1000");
            for (long id = 20; id <= 22; id++) {
                t.sell(id).account(10).user(10).limit(92).qty(1000).ioc().send();
            }
            t.sell(30).account(11).user(11).limit(92).qty(1000).ioc().send();
            t.advance(RACE);
            List<Long> order = aggressors(t);
            assertEquals(4, order.size());
            assertTrue(order.indexOf(30L) < order.indexOf(21L), "account 11 is drawn in round one: " + order);
            assertTrue(order.indexOf(20L) < order.indexOf(21L) && order.indexOf(21L) < order.indexOf(22L),
                    "an account's orders apply in arrival order: " + order);
        }

        @Test
        void windowIsHalfOpenAnOrderAtExpiryStartsANewRace() {
            Scenario t = Scenario.of(taker(false)).book("1|BUY|2000|92|2000");
            t.sell(10).limit(92).qty(1000).ioc().send();
            t.advance(RACE).sell(11).limit(92).qty(1000).ioc().send();
            assertEquals(List.of(fill(1, 92, 1000)), t.fills(), "race 1 resolved before the new order");
            assertEquals(2, t.events(Event.RaceStarted.class).size());
        }

        @Test
        void nonCrossingAndIneligibleOrdersDoNotJoin() {
            s.sell(10).limit(93).qty(1000).send();
            s.relationship(11, 1, CreditEngine.NO_LINE);
            s.sell(11).limit(92).qty(1000).send();
            assertEquals(List.of(), s.events(Event.JoinedRace.class));
            assertEquals(List.of(11L), s.book().orderIdsAt(Side.SELL, 92), "rests: the book is crossed");
        }

        @Test
        void marketOrdersAndAggressivePegsJoin() {
            Scenario t = Scenario.of(taker(false)).book("1|BUY|1000|92|1000", "2|SELL|1000|94|1000");
            t.sell(10).market().qty(1000).ioc().send();
            t.buy(11).peg(PegReference.NEAR_TOUCH, 2).qty(1000).send();
            assertEquals(2, t.events(Event.JoinedRace.class).size());
            assertEquals(2, t.events(Event.RaceStarted.class).size(), "one race per aggressor side");
            t.advance(RACE);
            assertEquals(2, t.fills().size());
        }

        @Test
        void crossingAmendJoins() {
            Scenario t = Scenario.of(taker(false)).book("1|BUY|1000|92|1000");
            t.sell(10).limit(93).qty(1000).send();
            t.replace(10).price(92).send();
            assertEquals(1, t.events(Event.JoinedRace.class).size());
        }

        @Test
        void fokIsCheckedWhenApplied() {
            s.sell(10).limit(92).qty(1000).fok().send();
            s.sell(11).limit(92).qty(1000).fok().send();
            s.advance(RACE);
            assertEquals(1, s.fills().size());
            assertEquals(1, s.events(Event.Cancelled.class).stream().filter(c -> c.reason() == CancelReason.FOK_UNFILLED).count());
        }

        @Test
        void raceOrdersCannotBeAmended() {
            s.sell(10).limit(92).qty(1000).day().send();
            s.clear().replace(10).qty(2000).send();
            assertEquals(List.of(new Event.RequestRejected(0, 10, RequestType.REPLACE, RequestRejectReason.PENDING_IN_RACE)),
                    s.events());
        }

        @Test
        void withoutQueueCancelsAMakerCanPullLiquidityDuringTheRace() {
            s.sell(10).limit(92).qty(1000).ioc().send();
            s.advance(MS).cancel(1);
            s.advance(RACE);
            assertEquals(List.of(), s.fills());
            assertTrue(s.events().contains(new Event.Cancelled(RACE, 10, CancelReason.IOC_UNFILLED, 1000)));
        }

        @Test
        void differentSeedsCanDrawDifferently() {
            List<Long> a = run(1);
            boolean differs = false;
            for (long seed = 2; seed < 20 && !differs; seed++) {
                differs = !run(seed).equals(a);
            }
            assertTrue(differs);
            assertEquals(a, run(1), "same seed, same draw");
        }

        private List<Long> run(long seed) {
            Scenario t = Scenario.of(taker(false), seed).book("1|BUY|1000|92|1000", "2|BUY|1000|92|1000");
            for (long id = 10; id < 16; id++) {
                t.sell(id).limit(92).qty(1000).ioc().send();
            }
            t.advance(RACE);
            return aggressors(t);
        }
    }

    @Nested
    class QueueCancels {
        Scenario s = Scenario.of(taker(true)).book("1|BUY|2000|92|2000");

        @Test
        void makerCancelIsHeldUntilTheRaceCompletes() {
            s.sell(10).limit(92).qty(1000).ioc().send();
            s.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.Queued(MS, 1, RequestType.CANCEL, RACE)), s.events());
            s.clear().advance(RACE);
            assertEquals(List.of(fill(1, 92, 1000)), s.fills());
            assertTrue(s.events().contains(new Event.Cancelled(RACE, 1, CancelReason.PARTICIPANT, 1000)));
        }

        @Test
        void heldCancelIsTooLateIfTheOrderFills() {
            Scenario t = Scenario.of(taker(true)).book("1|BUY|1000|92|1000");
            t.sell(10).limit(92).qty(1000).ioc().send();
            t.advance(MS).cancel(1);
            t.clear().advance(RACE);
            assertTrue(t.events().contains(new Event.RequestRejected(RACE, 1, RequestType.CANCEL, RequestRejectReason.TOO_LATE)));
        }

        @Test
        void takerCannotWithdrawFromTheRace() {
            s.sell(10).limit(92).qty(1000).day().send();
            s.advance(MS).cancel(10);
            s.clear().advance(RACE);
            assertEquals(List.of(fill(1, 92, 1000)), s.fills());
        }

        @Test
        void disconnectCancelsBypassTheHold() {
            s.sell(10).limit(92).qty(1000).ioc().send();
            s.clear().advance(MS).cancel(1, CancelSource.DISCONNECT);
            assertEquals(List.of(new Event.Cancelled(MS, 1, CancelReason.DISCONNECT, 2000)), s.events());
        }

        @Test
        void heldCancelWaitsForBothTtlAndRace() {
            Scenario t = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).takerRaceNanos(RACE)
                    .queueCancels(true).minTtlNanos(10 * MS).build()).book("1|BUY|2000|92|2000");
            t.sell(10).limit(92).qty(1000).ioc().send();
            t.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.Queued(MS, 1, RequestType.CANCEL, 10 * MS)), t.events());
            t.clear().advance(RACE);
            assertEquals(List.of(), t.events(Event.Cancelled.class).stream().filter(c -> c.orderId() == 1).toList());
            t.clear().advance(10 * MS);
            assertEquals(List.of(new Event.Cancelled(10 * MS, 1, CancelReason.PARTICIPANT, 1000)), t.events());
        }

        @Test
        void cancelsAreImmediateWhenNoRaceIsRunning() {
            s.clear().cancel(1);
            assertEquals(List.of(new Event.Cancelled(0, 1, CancelReason.PARTICIPANT, 2000)), s.events());
        }
    }

    @Nested
    class Maker {
        Scenario s = Scenario.of(maker());

        @Test
        void newLevelStartsAMakerRaceAndOrdersAreHeldOffTheBook() {
            s.buy(10).limit(92).qty(1000).send();
            s.advance(MS).buy(11).limit(92).qty(1000).send();
            assertEquals(1, s.events(Event.RaceStarted.class).size());
            assertEquals(List.of(), s.book().orderIdsAt(Side.BUY, 92));
            s.advance(RACE);
            assertEquals(2, s.book().orderIdsAt(Side.BUY, 92).size());
        }

        @Test
        void lotteryDecidesTimePriorityFairly() {
            int earlyFirst = 0;
            int runs = 2000;
            for (long seed = 0; seed < runs; seed++) {
                Scenario t = Scenario.of(maker(), seed);
                t.buy(10).limit(92).qty(1000).send();
                t.advance(4 * MS).buy(11).limit(92).qty(1000).send();
                t.advance(MS);
                if (t.book().orderIdsAt(Side.BUY, 92).get(0) == 10) {
                    earlyFirst++;
                }
            }
            assertTrue(earlyFirst > runs * 0.45 && earlyFirst < runs * 0.55, "early order first " + earlyFirst + "/" + runs);
        }

        @Test
        void existingLevelIsJoinedNormally() {
            s.buy(10).limit(92).qty(1000).send();
            s.advance(RACE);
            s.clear().buy(11).limit(92).qty(1000).send();
            assertEquals(List.of(), s.events(Event.JoinedRace.class));
            assertEquals(List.of(10L, 11L), s.book().orderIdsAt(Side.BUY, 92));
        }

        @Test
        void pendingMakerOrdersAreNotMatchableUntilApplied() {
            s.buy(10).limit(92).qty(1000).send();
            s.sell(11).limit(92).qty(1000).send(); // new ask level: its own maker race
            assertEquals(List.of(), s.fills());
            s.advance(RACE);
            assertEquals(1, s.fills().size(), "applied in lottery order, the second one trades");
        }

        @Test
        void pendingMakerOrderCanBeCancelled() {
            s.buy(10).limit(92).qty(1000).send();
            s.clear().cancel(10);
            assertEquals(List.of(new Event.Cancelled(0, 10, CancelReason.PARTICIPANT, 1000)), s.events());
            s.advance(RACE);
            assertTrue(s.book().isEmpty());
        }
    }
}
