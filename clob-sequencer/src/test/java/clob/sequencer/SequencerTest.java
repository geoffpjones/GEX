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
        assertEquals(3, Journal.replay(dir, (b, o, l) -> { }), "the time input is journalled");
        assertTrue(CreditEngine.RATE_SCALE > 0);
    }
}
