package clob.engine;

import static clob.testkit.Fill.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.MatchPriority;
import clob.testkit.Event;
import clob.testkit.Scenario;
import clob.testkit.TestInstruments;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CancelReplaceTest {
    static final long MS = 1_000_000L;

    @Nested
    class WithoutTtl {
        Scenario s = Scenario.of(TestInstruments.visibility()).book("1|BUY|2000|92|2000", "2|BUY|1000|92|1000");

        @Test
        void cancelRemovesOrder() {
            s.cancel(2);
            assertEquals(List.of(new Event.Cancelled(0, 2, CancelReason.PARTICIPANT, 1000)), s.events());
            assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
        }

        @Test
        void cancelUnknownOrder() {
            s.cancel(99);
            assertEquals(List.of(new Event.RequestRejected(0, 99, RequestType.CANCEL, RequestRejectReason.UNKNOWN_ORDER)),
                    s.events());
        }

        @Test
        void cancelByUserNotEntitledToAccount() {
            s.cancelAs(1, 5);
            assertEquals(List.of(new Event.RequestRejected(0, 1, RequestType.CANCEL, RequestRejectReason.NOT_ENTITLED)),
                    s.events());
        }

        @Test
        void qtyDecreaseKeepsPriority() {
            s.replace(1).qty(1000).send();
            assertEquals(List.of(1L, 2L), s.book().orderIdsAt(Side.BUY, 92));
            assertEquals(2000, s.book().visibleQtyAt(Side.BUY, 92));
        }

        @Test
        void qtyIncreaseLosesPriority() {
            s.replace(1).qty(3000).send();
            assertEquals(List.of(2L, 1L), s.book().orderIdsAt(Side.BUY, 92));
        }

        @Test
        void priceChangeLosesPriorityAndCanTrade() {
            s.sell(3).limit(94).qty(1000).send();
            s.clear().replace(2).price(94).send();
            assertEquals(List.of(fill(3, 94, 1000)), s.fills());
            assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 92));
        }

        @Test
        void replaceToQtyAtOrBelowFilledIsRejected() {
            s.sell(3).limit(92).qty(1000).ioc().send(); // fills 1000 of order 1
            s.clear().replace(1).qty(1000).send();
            assertEquals(List.of(new Event.RequestRejected(0, 1, RequestType.REPLACE,
                    RequestRejectReason.QTY_NOT_ABOVE_FILLED)), s.events());
        }

        @Test
        void massCancelByAccount() {
            s.buy(3).limit(91).qty(1000).account(1).user(1).send();
            s.clear().massCancel(MassCancelScope.ACCOUNT, 1, 1, CancelSource.PARTICIPANT);
            assertEquals(List.of(new Event.Cancelled(0, 1, CancelReason.PARTICIPANT, 2000),
                    new Event.Cancelled(0, 3, CancelReason.PARTICIPANT, 1000)), s.events());
            assertEquals(List.of(2L), s.book().orderIdsAt(Side.BUY, 92));
        }

        @Test
        void massCancelForUnentitledAccountIsIgnored() {
            s.massCancel(MassCancelScope.ACCOUNT, 1, 2, CancelSource.PARTICIPANT);
            assertEquals(List.of(), s.events());
        }
    }

    @Nested
    class MinTtlReject {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY)
                .minTtlNanos(5 * MS).rejectOnTtl(true).build());

        @Test
        void cancelInsideTtlIsRejectedAndAllowedAfter() {
            s.buy(1).limit(92).qty(1000).send();
            s.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.RequestRejected(MS, 1, RequestType.CANCEL,
                    RequestRejectReason.INSIDE_MIN_TTL)), s.events());
            s.clear().advance(4 * MS).cancel(1);
            assertEquals(List.of(new Event.Cancelled(5 * MS, 1, CancelReason.PARTICIPANT, 1000)), s.events());
        }

        @Test
        void disconnectAndAdminCancelsBypassTtl() {
            s.buy(1).limit(92).qty(1000).send();
            s.buy(2).limit(92).qty(1000).send();
            s.clear().advance(MS).cancel(1, CancelSource.DISCONNECT).cancel(2, CancelSource.ADMIN);
            assertEquals(List.of(new Event.Cancelled(MS, 1, CancelReason.DISCONNECT, 1000),
                    new Event.Cancelled(MS, 2, CancelReason.ADMIN, 1000)), s.events());
        }

        @Test
        void amendInsideTtlIsRejected() {
            s.buy(1).limit(92).qty(1000).send();
            s.clear().advance(MS).replace(1).price(91).send();
            assertEquals(List.of(new Event.RequestRejected(MS, 1, RequestType.REPLACE,
                    RequestRejectReason.INSIDE_MIN_TTL)), s.events());
        }

        @Test
        void appliedAmendRestartsTtl() {
            s.buy(1).limit(92).qty(1000).send();
            s.advance(5 * MS).replace(1).price(91).send();
            s.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.RequestRejected(6 * MS, 1, RequestType.CANCEL,
                    RequestRejectReason.INSIDE_MIN_TTL)), s.events());
        }
    }

    @Nested
    class MinTtlQueue {
        Scenario s = Scenario.of(TestInstruments.example(MatchPriority.VISIBILITY)
                .minTtlNanos(5 * MS).rejectOnTtl(false).build());

        @Test
        void cancelInsideTtlIsQueuedThenApplied() {
            s.buy(1).limit(92).qty(1000).send();
            s.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.Queued(MS, 1, RequestType.CANCEL, 5 * MS)), s.events());
            s.clear().advance(10 * MS);
            assertEquals(List.of(new Event.Cancelled(5 * MS, 1, CancelReason.PARTICIPANT, 1000)), s.events());
        }

        @Test
        void queuedCancelDoesNotStopFillsAndIsTooLateIfFilled() {
            s.buy(1).limit(92).qty(1000).send();
            s.advance(MS).cancel(1);
            s.clear().advance(MS).sell(2).limit(92).qty(1000).ioc().send();
            assertEquals(List.of(fill(1, 92, 1000)), s.fills());
            assertTrue(s.events().contains(new Event.RequestRejected(2 * MS, 1, RequestType.CANCEL,
                    RequestRejectReason.TOO_LATE)));
        }

        @Test
        void queuedAmendAppliedAtRelease() {
            s.buy(1).limit(92).qty(1000).send();
            s.clear().advance(MS).replace(1).price(91).send();
            assertEquals(List.of(new Event.Queued(MS, 1, RequestType.REPLACE, 5 * MS)), s.events());
            s.clear().advance(10 * MS);
            assertEquals(List.of(new Event.Replaced(5 * MS, 1, 91, 1000, 1000)), s.events());
            assertEquals(List.of(1L), s.book().orderIdsAt(Side.BUY, 91));
        }

        @Test
        void laterCancelSupersedesQueuedAmend() {
            s.buy(1).limit(92).qty(1000).send();
            s.advance(MS).replace(1).price(91).send();
            s.clear().advance(MS).cancel(1);
            assertEquals(List.of(new Event.RequestRejected(2 * MS, 1, RequestType.REPLACE, RequestRejectReason.SUPERSEDED),
                    new Event.Queued(2 * MS, 1, RequestType.CANCEL, 5 * MS)), s.events());
            s.clear().advance(10 * MS);
            assertEquals(List.of(new Event.Cancelled(5 * MS, 1, CancelReason.PARTICIPANT, 1000)), s.events());
        }

        @Test
        void secondCancelWhileQueuedIsRejected() {
            s.buy(1).limit(92).qty(1000).send();
            s.advance(MS).cancel(1);
            s.clear().cancel(1);
            assertEquals(List.of(new Event.RequestRejected(MS, 1, RequestType.CANCEL,
                    RequestRejectReason.CANCEL_ALREADY_PENDING)), s.events());
        }
    }
}
