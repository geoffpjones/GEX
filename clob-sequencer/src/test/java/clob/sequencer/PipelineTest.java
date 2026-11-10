package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditType;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The threaded path: gateway threads → sequencer thread → engine thread. */
class PipelineTest {
    @TempDir
    Path dir;

    @Test
    void concurrentGatewaysThenReplayIsByteIdentical() throws Exception {
        List<byte[]> admin = new Fixture().admin(9).inputs;
        List<byte[]> flow = new Fixture().flow(9, 20_000).inputs;
        Fixture.Capture live = new Fixture.Capture();
        MatchingEngine engine = Fixture.engine(Fixture.refData(), new OutputEncoder(live));
        try (Journal journal = Journal.open(dir, 1 << 20);
                Pipeline p = new Pipeline(engine, journal, Clock.system(), (s, t, ty, r) -> { }, 0, 0, 1 << 16)) {
            p.start();
            submitAll(p, admin);
            p.drain();
            List<Thread> gateways = new ArrayList<>();
            for (int g = 0; g < 3; g++) {
                int gw = g;
                Thread t = new Thread(() -> {
                    List<byte[]> mine = new ArrayList<>();
                    for (int i = gw; i < flow.size(); i += 3) {
                        mine.add(flow.get(i));
                    }
                    submitAll(p, mine);
                });
                gateways.add(t);
                t.start();
            }
            for (Thread t : gateways) {
                t.join();
            }
        }
        byte[] liveBytes = live.toByteArray();
        assertTrue(ReplayTest.count(liveBytes, OutputEncoder.TRADE) > 500, "flow exercised");
        assertArrayEquals(liveBytes, ReplayTest.replay(dir), "the journal captured the interleaving");
    }

    @Test
    void timersFireWithNoOrderFlow() throws Exception {
        Fixture.Capture live = new Fixture.Capture();
        MatchingEngine engine = Fixture.engine(Fixture.refData(), new OutputEncoder(live));
        Clock clock = Clock.system();
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        try (Journal journal = Journal.open(dir, 1 << 20);
                Pipeline p = new Pipeline(engine, journal, clock, (s, t, ty, r) -> { }, 0, 0, 1 << 16)) {
            p.start();
            p.submit(buf, 0, InputCodec.creditLine(buf, 0, 1, 1, CreditType.UNLIMITED, 0));
            p.submit(buf, 0, InputCodec.valueDates(buf, 0, 1, 20_000, -1));
            NewOrderCommand c = new NewOrderCommand();
            c.orderId = 1; c.instrumentId = 1; c.accountId = 1; c.userId = 1; c.side = Side.BUY;
            c.type = OrderType.LIMIT; c.tif = TimeInForce.GTT; c.price = 95; c.qty = 1000;
            c.expireTime = clock.nanos() + 50 * Fixture.MS;
            p.submit(buf, 0, InputCodec.newOrder(buf, 0, c));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (ReplayTest.count(snapshot(p, live), OutputEncoder.CANCELLED) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
        assertTrue(ReplayTest.count(live.toByteArray(), OutputEncoder.CANCELLED) == 1, "GTT expired via an injected time input");
        assertArrayEquals(live.toByteArray(), ReplayTest.replay(dir));
    }

    private static byte[] snapshot(Pipeline p, Fixture.Capture live) {
        p.drain();
        synchronized (live) {
            return live.toByteArray();
        }
    }

    private static void submitAll(Pipeline p, List<byte[]> msgs) {
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        for (byte[] m : msgs) {
            buf.putBytes(0, m);
            while (!p.submit(buf, 0, m.length)) {
                Thread.onSpinWait();
            }
        }
    }
}
