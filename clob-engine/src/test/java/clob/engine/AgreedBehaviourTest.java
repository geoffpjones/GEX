package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.refdata.FixingEvent;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour agreed in the requirements Q&A (PLAN M3). */
class AgreedBehaviourTest {
    static final long MS = 1_000_000L;

    Scenario visibility() {
        return Scenario.of(TestInstruments.visibility());
    }

    Scenario queueCancels() {
        return Scenario.of(TestInstruments.example(MatchPriority.PRICE).takerRaceNanos(5 * MS).queueCancels(true).build());
    }

    // ---------------------------------------------------------------- Q7c market orders

    @Test
    void marketOrderWithRestingTifIsRejected() {
        Scenario s = visibility();
        for (var tif : List.of(clob.refdata.TimeInForce.DAY, clob.refdata.TimeInForce.GTC)) {
            s.clear().sell(50).market().qty(1000).tif(tif).send();
            assertEquals(List.of(new Event.Rejected(0, 50, RejectReason.INVALID_TIF_FOR_TYPE)), s.events());
        }
    }

    // ---------------------------------------------------------------- Q7b holds

    @Test
    void onlyTheSideBeingTakenIsHeld() {
        Scenario s = queueCancels();
        s.book("1|BUY|1000|100|1000", "2|SELL|1000|110|1000");
        s.sell(3).limit(100).qty(1000).ioc().send(); // sell race: takes bids
        s.clear().cancel(2);
        assertEquals(new Event.Cancelled(0, 2, CancelReason.PARTICIPANT, 1000), s.events().get(0), "the offer is free");
        s.clear().cancel(1);
        assertEquals(new Event.Queued(0, 1, RequestType.CANCEL, 5 * MS), s.events().get(0), "the bid is held");
    }

    @Test
    void aTakerCannotWithdrawOnceInTheRace() {
        Scenario s = queueCancels();
        s.book("1|BUY|1000|100|1000");
        s.sell(3).limit(100).qty(2000).day().send();
        s.clear().cancel(3);
        assertEquals(new Event.Queued(0, 3, RequestType.CANCEL, 5 * MS), s.events().get(0));
        s.clear().advance(5 * MS);
        assertEquals(List.of(fill(1, 100, 1000)), s.fills(), "the race ran first");
        assertTrue(s.events().contains(new Event.Cancelled(5 * MS, 3, CancelReason.PARTICIPANT, 1000)), s.events().toString());
    }

    @Test
    void holdEndsWithItsOwnRaceNotAnOtherSidesLaterRace() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).takerRaceNanos(5 * MS).queueCancels(true).build());
        s.book("1|BUY|1000|100|1000", "2|SELL|1000|101|1000");
        s.sell(3).limit(100).qty(1000).ioc().send(); // sell race A expires at 5, holds bids
        s.advance(MS);
        s.buy(4).limit(101).qty(1000).ioc().send(); // buy race B expires at 6
        s.clear().cancel(1);
        assertEquals(new Event.Queued(MS, 1, RequestType.CANCEL, 5 * MS), s.events().get(0));
        s.clear().advance(5 * MS);
        assertEquals(1, s.events(Event.RequestRejected.class).size(), "bid 1 filled in race A, so the held cancel is too late");
    }

    // ---------------------------------------------------------------- legal entities and pools

    private Scenario entityScenario() {
        Instrument i = TestInstruments.visibility();
        RefData.Builder b = RefData.builder().add(i).addFixingEvent(new FixingEvent(0, "T", LocalTime.of(16, 0),
                ZoneId.of("UTC"), LocalTime.of(15, 55)));
        for (int a = 1; a <= Scenario.PARTIES; a++) {
            b.entitle(a, a);
        }
        b.entity(1, 100).entity(2, 100); // accounts 1 and 2 are one legal entity
        return Scenario.withRefData(i, b.build());
    }

    @Test
    void accountsOfOneEntityShareACreditPool() {
        Scenario s = entityScenario();
        s.creditLine(10, 1, CreditType.DSL, 1000); // the pool, owned by the entity
        assertEquals(null, s.engine().credit().setRelationship(2, 3, CreditEngine.ALL_INSTRUMENTS, 10), "account 2 may use it");
        s.relationship(1, 3, 10);
        s.creditLine(20, 3, CreditType.UNLIMITED, 0).relationship(3, 1, 20).relationship(3, 2, 20);
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.sell(3).limit(100).qty(1000).gtc().send();
        assertEquals(1, s.fills().size());
        s.clear().buy(2).limit(100).qty(1000).gtc().send();
        s.sell(67).account(3).user(3).limit(100).qty(1000).gtc().send();
        assertEquals(List.of(), s.fills(), "account 1 used the shared pool up");
    }

    // ---------------------------------------------------------------- Q29 self-trade

    @Test
    void selfTradeCancelsTheAggressorsRemainderAndPriorFillsStand() {
        Scenario s = visibility();
        s.book("1|BUY|1000|100|1000", "9|BUY|1000|100|1000");
        s.clear().sell(73).account(9).user(9).limit(100).qty(3000).day().send(); // same account as order 9
        assertEquals(List.of(fill(1, 100, 1000)), s.fills());
        assertEquals(new Event.Cancelled(0, 73, CancelReason.SELF_TRADE, 2000), s.events().get(s.events().size() - 1));
        assertEquals(List.of(9L), s.book().orderIdsAt(Side.BUY, 100), "the resting order is untouched");
    }

    @Test
    void selfTradeBetweenAccountsOfOneEntity() {
        Scenario s = entityScenario();
        s.creditLine(30, 1, CreditType.UNLIMITED, 0).creditLine(31, 2, CreditType.UNLIMITED, 0)
                .relationship(1, 2, 30).relationship(2, 1, 31);
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.clear().sell(2).limit(100).qty(1000).day().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 2, CancelReason.SELF_TRADE, 1000), s.events().get(1));
    }

    @Test
    void selfTradeIsCheckedBeforeCredit() {
        // An account has no credit with itself; the outcome is still self-trade, not a skip.
        Scenario s = visibility();
        s.book("9|BUY|1000|100|1000", "10|BUY|1000|100|1000");
        s.clear().sell(73).account(9).user(9).limit(100).qty(1000).ioc().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 73, CancelReason.SELF_TRADE, 1000), s.events().get(1));
    }

    @Test
    void fokStoppedBySelfTradeHasNoFillsAndNamesTheReason() {
        Scenario s = visibility();
        s.book("1|BUY|1000|100|1000", "9|BUY|1000|100|1000");
        s.clear().sell(73).account(9).user(9).limit(100).qty(2000).fok().send();
        assertEquals(List.of(), s.fills());
        assertEquals(new Event.Cancelled(0, 73, CancelReason.SELF_TRADE, 2000), s.events().get(1));
    }

    @Test
    void lockedHiddenOwnOrderIsNotReached() {
        Scenario s = visibility();
        s.book("9|BUY|0|93|1000", "2|BUY|1000|92|1000");
        s.clear().sell(73).account(9).user(9).limit(92).qty(1000).ioc().send();
        assertEquals(List.of(fill(2, 92, 1000)), s.fills());
    }

    @Test
    void midAndFixingSelfTrade() {
        Scenario s = visibility();
        s.book("1|BUY|1000|90|1000", "2|SELL|1000|100|1000");
        s.buy(9).mid(0).qty(1000).gtc().send();
        s.clear().sell(73).account(9).user(9).mid(0).qty(1000).gtc().send();
        assertEquals(new Event.Cancelled(0, 73, CancelReason.SELF_TRADE, 1000), s.events().get(1));
        s.fixingSchedule(100 * MS, 200 * MS);
        s.buy(19).account(11).user(11).fixing().qty(1000).send();
        s.clear().sell(75).account(11).user(11).fixing().qty(1000).send();
        assertEquals(new Event.Cancelled(0, 75, CancelReason.SELF_TRADE, 1000), s.events().get(1));
    }

    // ---------------------------------------------------------------- Q28 hidden trades

    @Test
    void newlyRestingDisplayedOrderTradesWithCrossingHiddenAtTheHiddenPrice() {
        Scenario s = visibility();
        s.book("1|BUY|0|93|1000", "2|BUY|1000|91|1000");
        s.clear().sell(10).limit(92).qty(1000).day().send();
        assertEquals(List.of(fill(1, 93, 1000)), s.fills());
        assertEquals(List.of(), s.book().orderIdsAt(Side.SELL, 92));
        assertEquals(List.of(), s.book().orderIdsAt(Side.BUY, 93));
    }

    @Test
    void residualKeepsRestingAfterTheHiddenIsDrained() {
        Scenario s = visibility();
        s.book("1|BUY|0|93|1000", "2|BUY|1000|91|1000");
        s.clear().sell(10).limit(92).qty(3000).day().send();
        assertEquals(List.of(fill(1, 93, 1000)), s.fills());
        assertEquals(2000, s.book().visibleQtyAt(Side.SELL, 92));
    }

    @Test
    void fullyHiddenRestingOrderTriggersNothing() {
        Scenario s = visibility();
        s.book("1|BUY|0|93|1000", "2|BUY|1000|91|1000");
        s.clear().sell(10).limit(92).qty(1000).show(0).day().send();
        assertEquals(List.of(), s.fills(), "two hidden orders can remain crossed");
    }

    @Test
    void icebergSweepsThroughHiddenUsingOnlyItsTipEachTime() {
        Scenario s = visibility();
        s.book("1|BUY|0|93|3000", "2|BUY|1000|91|1000");
        s.clear().sell(10).iceberg(92, 1000).qty(5000).day().send();
        assertEquals(List.of(fill(1, 93, 1000), fill(1, 93, 1000), fill(1, 93, 1000)), s.fills());
        assertEquals(1000, s.book().visibleQtyAt(Side.SELL, 92));
    }

    @Test
    void anAmendThatNewlyDisplaysTrades() {
        Scenario s = visibility();
        s.book("1|BUY|0|93|1000", "2|BUY|1000|91|1000", "3|SELL|1000|95|1000");
        s.clear().replace(3).price(92).send();
        assertEquals(List.of(fill(1, 93, 1000)), s.fills());
    }

    @Test
    void hiddenSweepHonoursCredit() {
        Scenario s = Scenario.withoutCredit(TestInstruments.visibility());
        s.bilateral(1, 3, CreditType.DSL, 1000);
        s.bilateral(2, 3, CreditType.UNLIMITED, 0);
        s.buy(1).limit(93).qty(5000).show(0).gtc().send();
        s.buy(2).limit(91).qty(1000).gtc().send();
        s.clear().sell(3).limit(92).qty(3000).day().send();
        assertEquals(List.of(fill(1, 93, 1000)), s.fills(), "clipped to the 1000 of credit, then it rests");
        assertEquals(2000, s.book().visibleQtyAt(Side.SELL, 92));
    }

    @Test
    void sweepAppliesUnderVisibilityOnly() {
        Scenario s = Scenario.of(TestInstruments.price());
        s.book("1|BUY|0|93|1000", "2|BUY|1000|91|1000");
        s.clear().sell(10).limit(92).qty(1000).day().send();
        assertEquals(List.of(fill(1, 93, 1000)), s.fills(), "PRICE matched it on arrival");
    }

    // ---------------------------------------------------------------- Q37 amends into maker races

    @Test
    void amendCreatingANewLevelEntersAMakerRace() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).makerRaceNanos(5 * MS).build());
        s.buy(1).limit(99).qty(1000).gtc().send();
        s.advance(5 * MS);
        s.clear().replace(1).price(100).requestId(7).send();
        assertEquals(1, s.events(Event.RaceStarted.class).size());
        assertEquals(List.of(), s.book().orderIdsAt(Side.BUY, 100), "off the book until the race resolves");
        s.clear().replace(1).price(98).send();
        assertEquals(RequestRejectReason.PENDING_IN_RACE, s.events(Event.RequestRejected.class).get(0).reason());
        s.advance(5 * MS);
        assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 100));
    }

    @Test
    void amendToAnExistingLevelRestsNormally() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).makerRaceNanos(5 * MS).build());
        s.buy(1).limit(99).qty(1000).gtc().send();
        s.buy(2).limit(100).qty(1000).gtc().send();
        s.advance(5 * MS);
        s.clear().replace(1).price(100).send();
        assertEquals(List.of(), s.events(Event.RaceStarted.class));
        assertEquals(List.of(2L, 1L), s.book().orderIdsAt(Side.BUY, 100));
    }

    @Test
    void requestIdsCorrelateEveryOutcome() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).minTtlNanos(10 * MS).build());
        s.buy(1).limit(99).qty(1000).gtc().send();
        s.clear();
        s.replace(1).price(98).requestId(11).send();
        s.replace(1).price(97).requestId(12).send();
        s.cancelRequest(1, 13);
        s.cancelRequest(1, 14);
        s.advance(10 * MS);
        assertEquals(List.of("QUEUED order=1 request=11", "REJECTED_SUPERSEDED order=1 request=11", "QUEUED order=1 request=12",
                "REJECTED_SUPERSEDED order=1 request=12", "QUEUED order=1 request=13",
                "REJECTED_CANCEL_ALREADY_PENDING order=1 request=14", "CANCELLED order=1 request=13"), s.requestLog());
    }

    // ---------------------------------------------------------------- Q38 halts

    @Test
    void haltCancelsMakerRaceOrdersAndReleasesHolds() {
        Scenario s = queueCancels();
        s.book("1|BUY|1000|100|1000");
        s.sell(3).limit(100).qty(1000).ioc().send();
        s.cancel(1); // held by the sell race
        s.clear().session(SessionState.HALTED);
        assertEquals(List.of(new Event.Session(0, 0, SessionState.HALTED), new Event.Cancelled(0, 3, CancelReason.HALTED, 1000),
                new Event.Cancelled(0, 1, CancelReason.PARTICIPANT, 1000)), s.events(), "the held cancel is released, not lost");
        assertFalse(s.book().takerRaceRunning(Side.SELL));
    }

    @Test
    void gttWaitingThroughAHaltIsAlreadyGone() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).takerRaceNanos(5 * MS).build());
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.sell(2).limit(100).qty(1000).gtt(8 * MS).send();
        s.clear().session(SessionState.HALTED);
        assertEquals(new Event.Cancelled(0, 2, CancelReason.HALTED, 1000), s.events().get(1));
        s.clear().advance(10 * MS);
        assertEquals(List.of(), s.events(), "its timer finds nothing to do");
    }

    // ---------------------------------------------------------------- missing data

    @Test
    void anInstrumentResumesWhenItsDataArrives() {
        Scenario s = visibility();
        assertEquals(SessionState.OPEN, s.book().session());
        s.rollValueDate(Scenario.SPOT_DATE + 1); // the value date is now in the past
        assertEquals(SessionState.HALTED, s.book().session());
        s.clear().buy(1).limit(100).qty(1000).send();
        assertEquals(List.of(new Event.Rejected(0, 1, RejectReason.MARKET_HALTED)), s.events());
        s.clear().valueDates(Scenario.SPOT_DATE + 1, -1);
        assertEquals(List.of(new Event.Session(0, 0, SessionState.OPEN)), s.events());
        s.buy(2).limit(100).qty(1000).send();
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.BUY, 100));
    }

    @Test
    void arrivingDataNeverReopensAnAdminHalt() {
        Scenario s = visibility();
        s.session(SessionState.HALTED).rollValueDate(Scenario.SPOT_DATE + 1);
        s.clear().valueDates(Scenario.SPOT_DATE + 1, -1);
        assertEquals(List.of(), s.events());
        assertEquals(SessionState.HALTED, s.book().session());
        s.session(SessionState.OPEN);
        assertEquals(SessionState.OPEN, s.book().session());
    }

    @Test
    void adminOpenDoesNotOverrideMissingData() {
        Scenario s = visibility();
        s.rollValueDate(Scenario.SPOT_DATE + 1).session(SessionState.CLOSED).session(SessionState.OPEN);
        assertEquals(SessionState.HALTED, s.book().session());
    }

    @Test
    void aDataHaltCancelsRaceOrders() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.PRICE).takerRaceNanos(5 * MS).build());
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.sell(2).limit(100).qty(1000).ioc().send();
        s.clear().rollValueDate(Scenario.SPOT_DATE + 1);
        assertTrue(s.events().contains(new Event.Cancelled(0, 2, CancelReason.HALTED, 1000)), s.events().toString());
    }

    // ---------------------------------------------------------------- pre-open

    @Test
    void preOpenCollectsThenOpensThroughOneLottery() {
        Scenario s = visibility();
        s.session(SessionState.PRE_OPEN);
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.sell(2).limit(100).qty(1000).gtc().send();
        assertEquals(List.of(), s.fills(), "no matching before the open");
        assertTrue(s.book().isEmpty(), "not on the book yet");
        s.clear().session(SessionState.OPEN);
        assertEquals(1, s.fills().size(), "crossing orders trade as they land");
        assertEquals(new Event.Session(0, 0, SessionState.OPEN), s.events().get(0));
    }

    @Test
    void preOpenAcceptsOnlyLimitAndIceberg() {
        Scenario s = visibility();
        s.session(SessionState.PRE_OPEN);
        s.clear().buy(1).market().qty(1000).ioc().send();
        s.buy(2).peg(PegReference.MID, 0).qty(1000).send();
        s.buy(3).mid(0).qty(1000).send();
        assertEquals(List.of(new Event.Rejected(0, 1, RejectReason.ORDER_TYPE_NOT_ALLOWED_IN_PRE_OPEN),
                new Event.Rejected(0, 2, RejectReason.ORDER_TYPE_NOT_ALLOWED_IN_PRE_OPEN),
                new Event.Rejected(0, 3, RejectReason.ORDER_TYPE_NOT_ALLOWED_IN_PRE_OPEN)), s.events());
    }

    @Test
    void preOpenCancelAndAmendIgnoreTtl() {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY).minTtlNanos(10 * MS).rejectOnTtl(true).build());
        s.session(SessionState.PRE_OPEN);
        s.buy(1).limit(100).qty(1000).gtc().send();
        s.buy(2).limit(99).qty(1000).gtc().send();
        s.clear().cancel(1).replace(2).price(98).qty(2000).send();
        assertEquals(List.of(new Event.Cancelled(0, 1, CancelReason.PARTICIPANT, 1000), new Event.Replaced(0, 2, 98, 2000, 2000)), s.events());
        s.session(SessionState.OPEN);
        assertEquals(List.of(2L), s.book().orderIdsAt(Side.BUY, 98));
    }

    @Test
    void preOpenLotteryIsAcrossSidesAndPricesAndReproducible() {
        List<List<Event>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            Scenario s = Scenario.of(TestInstruments.visibility(), 42);
            s.session(SessionState.PRE_OPEN);
            for (int id = 1; id <= 20; id++) {
                s.buy(id).limit(90 + id).qty(1000).gtc().send();
                s.sell(100 + id).limit(90 + id).qty(1000).gtc().send();
            }
            s.clear().session(SessionState.OPEN);
            runs.add(s.events());
        }
        assertEquals(runs.get(0), runs.get(1));
    }

    @Test
    void haltDuringPreOpenCancelsCollectedOrders() {
        Scenario s = visibility();
        s.session(SessionState.PRE_OPEN).buy(1).limit(100).qty(1000).gtc().send();
        s.clear().session(SessionState.HALTED);
        assertEquals(new Event.Cancelled(0, 1, CancelReason.HALTED, 1000), s.events().get(1));
    }

    @Test
    void restingOrdersDoNotMatchDuringPreOpen() {
        Scenario s = visibility();
        s.book("1|BUY|1000|100|1000");
        s.session(SessionState.PRE_OPEN);
        s.clear().sell(2).limit(100).qty(1000).gtc().send();
        assertEquals(List.of(), s.fills());
        s.session(SessionState.OPEN);
        assertEquals(List.of(fill(1, 100, 1000)), s.fills());
    }

    // ---------------------------------------------------------------- end of day timers

    @Test
    void endOfDayCompactsCancelledLongDatedTimers() {
        Scenario s = visibility();
        long far = 1_000_000 * MS;
        for (int id = 1; id <= 1000; id++) {
            s.buy(id).limit(100).qty(1000).gtt(far).send();
            s.cancel(id);
        }
        s.endOfDay();
        assertEquals(0, s.engine().liveOrderCount());
        assertEquals(Long.MAX_VALUE, s.engine().nextTimerDeadline(), "nothing is left to fire");
    }
}
