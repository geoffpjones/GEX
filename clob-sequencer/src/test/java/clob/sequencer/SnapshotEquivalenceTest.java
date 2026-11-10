package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.engine.MatchingEngine;
import clob.refdata.RefData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** An engine restored from a snapshot must behave byte-identically to one that never stopped. */
class SnapshotEquivalenceTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void restoredEngineMatchesTheOriginal(int seed) throws Exception {
        List<byte[]> inputs = new Fixture().admin(seed).flow(seed, 4000).inputs;
        RefData refData = Fixture.refData();
        Fixture.Capture a = new Fixture.Capture();
        MatchingEngine original = Fixture.engine(refData, new OutputEncoder(a));
        EngineDispatcher da = new EngineDispatcher(original, (s, t, ty, r) -> { });
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        int cut = inputs.size() * seed / 7; // a different point each time, with races, pegs and timers in flight
        for (int i = 0; i < cut; i++) {
            feed(buf, inputs.get(i), i, da);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            original.save(out);
        }
        int before = a.bytes.size();
        Fixture.Capture b = new Fixture.Capture();
        MatchingEngine restored = Fixture.engine(refData, new OutputEncoder(b));
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored.restore(in);
        }
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(again)) {
            restored.save(out);
        }
        assertArrayEquals(bytes.toByteArray(), again.toByteArray(), "saving what was restored gives the same image");
        EngineDispatcher db = new EngineDispatcher(restored, (s, t, ty, r) -> { });
        for (int i = cut; i < inputs.size(); i++) {
            feed(buf, inputs.get(i), i, da);
            feed(buf, inputs.get(i), i, db);
        }
        byte[] rest = java.util.Arrays.copyOfRange(a.toByteArray(), before, a.bytes.size());
        assertTrue(rest.length > 1000);
        assertArrayEquals(rest, b.toByteArray());
    }

    private static void feed(UnsafeBuffer buf, byte[] m, int index, EngineDispatcher d) {
        buf.putBytes(0, m);
        InputCodec.stamp(buf, 0, index + 1, Fixture.T0 + index * 2 * Fixture.MS);
        d.onInput(buf, 0, m.length);
    }
}
