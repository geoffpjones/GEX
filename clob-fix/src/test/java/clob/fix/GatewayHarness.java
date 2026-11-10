package clob.fix;

import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.Clock;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/** Pipeline + engine + FIX gateway on a free port. Users 1..3 trade accounts 1..3 without credit limits. */
final class GatewayHarness implements AutoCloseable {
    static final String COMP_ID = "CLOB";

    final RefData refData;
    final Journal journal;
    final Pipeline pipeline;
    final clob.marketdata.MarketDataService marketData;
    final FixGateway gateway;
    private final AgentRunner runner;

    GatewayHarness(Path dir, List<FixSessionConfig> sessions) throws Exception {
        this(dir, sessions, 1 << 16, 1 << 20);
    }

    /** {@code inboundRing} and {@code outputRing} are the bounded queues in bytes (tiny in overload tests). */
    GatewayHarness(Path dir, List<FixSessionConfig> sessions, int inboundRing, int outputRing) throws Exception {
        RefData.Builder b = RefData.builder().add(Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(5)
                .priceIncrement(1).minQty(1000).qtyIncrement(1000).maxQty(10_000_000).minTtlNanos(0).build())
                .add(Instrument.builder(1, "EUR/USD", "TTL").pricePrecision(5).priceIncrement(1).minQty(1000)
                        .qtyIncrement(1000).maxQty(10_000_000).minTtlNanos(150_000_000L).build());
        for (int u = 1; u <= 3; u++) {
            b.entitle(u, u);
        }
        refData = b.build();
        OutputRing out = new OutputRing(outputRing);
        MatchingEngine engine = new MatchingEngine(refData, EngineConfig.defaults(), new CreditEngine(8, refData),
                new OutputEncoder(out));
        journal = Journal.open(dir, 1 << 20);
        pipeline = new Pipeline(engine, journal, Clock.system(), (s, t, ty, r) -> { }, 0, 0, inboundRing);
        marketData = new clob.marketdata.MarketDataService(refData, EngineConfig.defaults(), 8);
        pipeline.addFollower(marketData);
        pipeline.enableMarketDataTicks(5_000_000L);
        pipeline.start();
        UnsafeBuffer cmd = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        pipeline.submit(cmd, 0, InputCodec.usdRate(cmd, 0, "EUR", CreditEngine.RATE_SCALE));
        pipeline.submit(cmd, 0, InputCodec.valueDates(cmd, 0, 0, 20_000, -1));
        pipeline.submit(cmd, 0, InputCodec.valueDates(cmd, 0, 1, 20_000, -1));
        for (int a = 1; a <= 3; a++) {
            pipeline.submit(cmd, 0, InputCodec.creditLine(cmd, 0, a, a, CreditType.UNLIMITED, 0));
            for (int c = 1; c <= 3; c++) {
                if (a != c) {
                    pipeline.submit(cmd, 0, InputCodec.creditRelationship(cmd, 0, a, c, CreditEngine.ALL_INSTRUMENTS, a));
                }
            }
        }
        pipeline.drain();
        gateway = new FixGateway(new FixGatewayConfig(COMP_ID, 0, 1, sessions, 5_000), refData, pipeline, out,
                marketData, System::currentTimeMillis);
        runner = new AgentRunner(new BackoffIdleStrategy(100, 10, 1_000, 1_000_000), Throwable::printStackTrace, null,
                gateway);
        AgentRunner.startOnThread(runner);
    }

    int port() {
        return gateway.port();
    }

    @Override
    public void close() throws java.io.IOException {
        runner.close();
        gateway.close();
        pipeline.close();
        journal.close();
    }
}
