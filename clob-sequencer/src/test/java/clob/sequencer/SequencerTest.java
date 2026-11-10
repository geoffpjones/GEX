package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelReason;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import clob.testkit.Event;
import clob.testkit.RecordingListener;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequencerTest {
    @TempDir
    Path dir;

    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));

    @Test
    void stampsAreMonotonicEvenIfTheClockGoesBackwards() {
        long[] clock = {100};
        List<long[]> seen = new ArrayList<>();
        try (Journal j = Journal.open(dir, 8192)) {
            Sequencer s = new Sequencer(j, () -> clock[0], (b, o, l) ->
                    seen.add(new long[] {InputCodec.sequence(b, o), InputCodec.time(b, o)}), 0, 0);
            for (long t : new long[] {100, 200, 150, 300}) {
                clock[0] = t;
                s.onCommand(buf, 0, InputCodec.timeAdvance(buf, 0));
            }
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), seen.stream().map(x -> x[0]).toList());
        assertEquals(List.of(100L, 200L, 200L, 300L), seen.stream().map(x -> x[1]).toList());
        assertEquals(4, Journal.replay(dir, (b, o, l) -> { }));
    }

    @Test
    void injectsTimeWhenTheEnginesNextTimerIsDue() {
        RecordingListener rec = new RecordingListener();
        MatchingEngine engine = Fixture.engine(Fixture.refData(), rec);
        EngineDispatcher d = new EngineDispatcher(engine, (s, t, ty, r) -> { });
        long[] clock = {Fixture.T0};
        try (Journal j = Journal.open(dir, 8192)) {
            Sequencer s = new Sequencer(j, () -> clock[0], d, 0, 0);
            s.onCommand(buf, 0, InputCodec.creditLine(buf, 0, 1, 1, CreditType.UNLIMITED, 0));
            s.onCommand(buf, 0, InputCodec.valueDates(buf, 0, 1, 20_000, -1));
            NewOrderCommand c = new NewOrderCommand();
            c.orderId = 1; c.instrumentId = 1; c.accountId = 1; c.userId = 1; c.side = Side.BUY;
            c.type = OrderType.LIMIT; c.tif = TimeInForce.GTT; c.price = 95; c.qty = 1000;
            c.expireTime = Fixture.T0 + 10 * Fixture.MS;
            s.onCommand(buf, 0, InputCodec.newOrder(buf, 0, c));
            clock[0] = Fixture.T0 + 5 * Fixture.MS;
            assertFalse(s.poll(engine.nextTimerDeadline()));
            clock[0] = Fixture.T0 + 12 * Fixture.MS;
            assertTrue(s.poll(engine.nextTimerDeadline()));
        }
        assertTrue(rec.events().contains(new Event.Cancelled(Fixture.T0 + 10 * Fixture.MS, 1, CancelReason.EXPIRED, 1000)),
                "expired at its deadline, triggered by the injected time input");
        assertEquals(4, Journal.replay(dir, (b, o, l) -> { }), "the time input is journalled");
        assertTrue(CreditEngine.RATE_SCALE > 0);
    }

    @Test
    void endOfDayFiresAtTheLocalTimeAcrossDstAndOnceAfterAnOutage() {
        java.time.ZoneId ny = java.time.ZoneId.of("America/New_York");
        java.time.LocalTime five = java.time.LocalTime.of(17, 0);
        long summer = java.time.ZonedDateTime.of(2026, 7, 1, 16, 59, 0, 0, ny).toEpochSecond() * 1_000_000_000L;
        long[] clock = {summer};
        List<Integer> types = new ArrayList<>();
        try (Journal j = Journal.open(dir, 1 << 16)) {
            Sequencer s = new Sequencer(j, () -> clock[0], (b, o, l) -> types.add(InputCodec.type(b, o)), 0, 0);
            s.enableEndOfDay(five, ny);
            s.poll(Long.MAX_VALUE);
            assertEquals(List.of(), types, "not yet");
            clock[0] = summer + 60_000_000_000L; // exactly 17:00 EDT
            s.poll(Long.MAX_VALUE);
            assertEquals(List.of(InputCodec.END_OF_DAY), types);
            clock[0] += 3_600_000_000_000L * 24 * 30; // a month later, as if the node had been down
            s.poll(Long.MAX_VALUE);
            s.poll(Long.MAX_VALUE);
            assertEquals(2, types.size(), "one catch-up cut, not thirty");
        }
        // On a day whose offset differs (EST), 17:00 is 22:00 UTC rather than 21:00.
        long winter = java.time.ZonedDateTime.of(2026, 12, 1, 17, 0, 0, 0, ny).toEpochSecond();
        assertEquals(22, java.time.Instant.ofEpochSecond(winter).atZone(java.time.ZoneOffset.UTC).getHour());
    }
}
