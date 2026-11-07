package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.engine.MatchingEngine;
import clob.refdata.RefData;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The journal is the source of truth: replaying it must reproduce the live output byte for byte. */
class ReplayTest {
    @TempDir
    Path dir;

    record Live(byte[] output, long lastSequence, long lastTime) {}

    /** Feeds inputs [from, to) through a sequencer. Polls for timers after each input. */
    static Live live(List<byte[]> inputs, int from, int to, Journal journal, MatchingEngine engine,
            EngineDispatcher dispatcher, Fixture.StepClock clock, long lastSeq, long lastTime, Fixture.Capture cap) {
        Sequencer seq = new Sequencer(journal, clock, dispatcher, lastSeq, lastTime);
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        for (int i = from; i < to; i++) {
            byte[] m = inputs.get(i);
            buf.putBytes(0, m);
            seq.onCommand(buf, 0, m.length);
            seq.poll(engine.nextTimerDeadline());
        }
        return new Live(cap.toByteArray(), seq.lastSequence(), seq.lastTime());
    }

    static byte[] replay(Path dir) {
        RefData rd = Fixture.refData();
        Fixture.Capture cap = new Fixture.Capture();
        MatchingEngine engine = Fixture.engine(rd, new OutputEncoder(cap));
        Journal.replay(dir, new EngineDispatcher(engine, (s, t, ty, r) -> { }));
        return cap.toByteArray();
    }

    static int count(byte[] output, int type) {
        UnsafeBuffer b = new UnsafeBuffer(output);
        int n = 0;
        for (int p = 0; p < output.length; p += b.getInt(p, InputCodec.ORDER)) {
            if (b.getInt(p + 4, InputCodec.ORDER) == type) {
                n++;
            }
        }
        return n;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void replayIsByteIdentical(long seed) {
        List<byte[]> inputs = new Fixture().admin(seed).flow(seed, 3000).inputs;
        RefData rd = Fixture.refData();
        Fixture.Capture cap = new Fixture.Capture();
        MatchingEngine engine = Fixture.engine(rd, new OutputEncoder(cap));
        Live live;
        try (Journal j = Journal.open(dir, 1 << 20)) {
            live = live(inputs, 0, inputs.size(), j, engine, new EngineDispatcher(engine, (s, t, ty, r) -> { }),
                    new Fixture.StepClock(seed, Fixture.T0), 0, 0, cap);
        }
        assertTrue(count(live.output(), OutputEncoder.TRADE) > 200, "flow exercised");
        assertTrue(count(live.output(), OutputEncoder.RACE_STARTED) > 20, "races exercised");
        assertTrue(count(live.output(), OutputEncoder.SESSION_STATE) > 10, "halts exercised");
        assertArrayEquals(live.output(), replay(dir));
        assertArrayEquals(live.output(), replay(dir), "and again");
    }

    @ParameterizedTest
    @ValueSource(longs = {4, 5})
    void recoveryAfterACrashMatchesAnUninterruptedRun(long seed) {
        List<byte[]> inputs = new Fixture().admin(seed).flow(seed, 3000).inputs;
        int crashAt = inputs.size() / 2;

        // Uninterrupted.
        Fixture.Capture full = new Fixture.Capture();
        MatchingEngine e1 = Fixture.engine(Fixture.refData(), new OutputEncoder(full));
        try (Journal j = Journal.open(dir.resolve("a"), 1 << 20)) {
            live(inputs, 0, inputs.size(), j, e1, new EngineDispatcher(e1, (s, t, ty, r) -> { }),
                    new Fixture.StepClock(seed, Fixture.T0), 0, 0, full);
        }

        // Crash half way, recover by replay, continue.
        Path b = dir.resolve("b");
        Fixture.StepClock clock = new Fixture.StepClock(seed, Fixture.T0);
        Fixture.Capture before = new Fixture.Capture();
        MatchingEngine e2 = Fixture.engine(Fixture.refData(), new OutputEncoder(before));
        try (Journal j = Journal.open(b, 1 << 20)) {
            live(inputs, 0, crashAt, j, e2, new EngineDispatcher(e2, (s, t, ty, r) -> { }), clock, 0, 0, before);
        }
        // e2 is gone. Recover.
        Fixture.Capture recovered = new Fixture.Capture();
        MatchingEngine e3 = Fixture.engine(Fixture.refData(), new OutputEncoder(recovered));
        EngineDispatcher d3 = new EngineDispatcher(e3, (s, t, ty, r) -> { });
        long[] last = new long[2];
        Journal.replay(b, (buf, o, l) -> {
            d3.onInput(buf, o, l);
            last[0] = InputCodec.sequence(buf, o);
            last[1] = InputCodec.time(buf, o);
        });
        assertArrayEquals(before.toByteArray(), recovered.toByteArray(), "replay rebuilt the pre-crash state");
        try (Journal j = Journal.open(b, 1 << 20)) {
            live(inputs, crashAt, inputs.size(), j, e3, d3, clock, last[0], last[1], recovered);
        }
        assertArrayEquals(full.toByteArray(), recovered.toByteArray());
        assertEquals(full.events, recovered.events);
    }
}
