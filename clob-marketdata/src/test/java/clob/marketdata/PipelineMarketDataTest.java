package clob.marketdata;

import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.NullEngineListener;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import clob.sequencer.Clock;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.Pipeline;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Market data as a pipeline follower on its own thread, driven by wall-clock ticks. */
class PipelineMarketDataTest {
    @TempDir
    Path dir;

    @Test
    void ticksOnTheWallClockPublishUpdates() throws Exception {
        MdHarness setup = new MdHarness(MdHarness.fourDp().mdIntervalNanos(5_000_000L).build());
        MatchingEngine engine = new MatchingEngine(setup.refData, EngineConfig.defaults(), new CreditEngine(8, setup.refData),
                NullEngineListener.INSTANCE);
        MarketDataService md = new MarketDataService(setup.refData, EngineConfig.defaults(), 8);
        CopyOnWriteArrayList<String> views = new CopyOnWriteArrayList<>();
        md.subscribe(1, 0, 0, 0, false, (id, boundary, view) -> views.add(view.toString()));
        UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        try (Journal j = Journal.open(dir, 1 << 20);
                Pipeline p = new Pipeline(engine, j, Clock.system(), (s, t, ty, r) -> { }, 0, 0, 1 << 16)) {
            p.addFollower(md);
            p.enableMarketDataTicks(5_000_000L);
            p.start();
            p.submit(b, 0, InputCodec.creditLine(b, 0, 1, 1, CreditType.UNLIMITED, 0));
            NewOrderCommand c = new NewOrderCommand();
            c.orderId = 1; c.instrumentId = 0; c.accountId = 1; c.userId = 1; c.side = Side.BUY;
            c.type = OrderType.LIMIT; c.tif = TimeInForce.GTC; c.price = 10000; c.qty = 1000;
            p.submit(b, 0, InputCodec.newOrder(b, 0, c));
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!views.contains("bids[10000x1000] asks[]") && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        }
        assertTrue(views.contains("bids[10000x1000] asks[]"), views.toString());
        assertTrue(md.cycleNanos().getTotalCount() > 0);
    }
}
