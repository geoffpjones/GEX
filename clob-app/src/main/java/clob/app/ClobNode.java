package clob.app;

import clob.credit.CreditEngine;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.fix.FixGateway;
import clob.fix.FixGatewayConfig;
import clob.marketdata.MarketDataService;
import clob.refdata.RefData;
import clob.sequencer.Clock;
import clob.sequencer.EngineDispatcher;
import clob.sequencer.FanOutSink;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;

/**
 * A primary CLOB node: recovers by replaying its journal, then runs the pipeline (sequencer, engine),
 * market data, FIX gateway, ops (drop copy, metrics), the admin/metrics HTTP API, and optionally
 * replication to a warm standby.
 */
public final class ClobNode implements AutoCloseable {
    /** Engine state plus output plumbing; built by recovery and shared with a standby that is promoted. */
    static final class Core {
        RefData refData;
        MatchingEngine engine;
        EngineDispatcher dispatcher;
        OutputRing gatewayRing;
        OutputRing opsRing;
        SwitchableSink sink;
        Metrics metrics;
        long lastSequence;
        long lastTime;
    }

    static Core recover(NodeConfig cfg) {
        try {
            Core c = new Core();
            c.refData = RefDataLoader.load(cfg.refDataFile());
            c.metrics = new Metrics();
            c.gatewayRing = new OutputRing(1 << 22);
            c.opsRing = new OutputRing(1 << 22);
            c.sink = new SwitchableSink(new FanOutSink(c.gatewayRing, c.opsRing));
            EngineConfig d = EngineConfig.defaults();
            c.engine = new MatchingEngine(c.refData, new EngineConfig(d.initialOrders(), d.maxLiveOrders(),
                    d.initialLevels(), d.initialLevelsPerSide(), d.initialTimers(), cfg.randomSeed()),
                    new CreditEngine(cfg.maxAccounts(), c.refData), new OutputEncoder(c.sink));
            c.dispatcher = new EngineDispatcher(c.engine, (s, t, ty, r) -> { });
            if (java.nio.file.Files.isDirectory(cfg.journalDir())) {
                Journal.replay(cfg.journalDir(), (b, o, l) -> {
                    c.dispatcher.onInput(b, o, l);
                    c.lastSequence = InputCodec.sequence(b, o);
                    c.lastTime = InputCodec.time(b, o);
                });
            }
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final NodeConfig cfg;
    private final Core core;
    private final Journal journal;
    private final Pipeline pipeline;
    private final MarketDataService marketData;
    private final ReplicationSender replication;
    private final FixGateway gateway;
    private final AgentRunner gatewayRunner;
    private final OpsAgent ops;
    private final AgentRunner opsRunner;
    private final AdminHttp admin;

    /** Cold start (or restart): replay the journal, then open for business. */
    public static ClobNode startPrimary(NodeConfig cfg) throws IOException {
        Core core = recover(cfg);
        return new ClobNode(cfg, core, Journal.open(cfg.journalDir(), 64 << 20));
    }

    ClobNode(NodeConfig cfg, Core core, Journal journal) throws IOException {
        this.cfg = cfg;
        this.core = core;
        this.journal = journal;
        Metrics metrics = core.metrics;
        this.pipeline = new Pipeline(core.engine, journal, Clock.system(), (s, t, ty, r) -> {
            metrics.inc("clob_admin_refused_total", 1);
            System.err.println("refused input seq=" + s + " type=" + ty + ": " + r);
        }, core.lastSequence, core.lastTime, 1 << 20);
        EngineConfig d = EngineConfig.defaults();
        this.marketData = new MarketDataService(core.refData, new EngineConfig(d.initialOrders(), d.maxLiveOrders(),
                d.initialLevels(), d.initialLevelsPerSide(), d.initialTimers(), cfg.randomSeed()), cfg.maxAccounts());
        Journal.replay(cfg.journalDir(), marketData); // bring the market data replica up to the same point
        pipeline.addFollower(marketData);
        this.replication = cfg.replicationPort() >= 0 ? new ReplicationSender(cfg.replicationPort(), cfg.journalDir()) : null;
        if (replication != null) {
            pipeline.addFollower(replication);
        }
        pipeline.enableMarketDataTicks(cfg.mdIntervalNanos());
        core.sink.enable();
        pipeline.start();
        this.gateway = new FixGateway(new FixGatewayConfig(cfg.fixCompId(), cfg.fixPort(), 1, cfg.fixSessions(), 10_000),
                core.refData, pipeline, core.gatewayRing, marketData, System::currentTimeMillis);
        this.gatewayRunner = new AgentRunner(new BackoffIdleStrategy(100, 10, 1_000, 100_000), Throwable::printStackTrace,
                null, gateway);
        AgentRunner.startOnThread(gatewayRunner);
        this.ops = new OpsAgent(core.opsRing, metrics, cfg.dataDir());
        this.opsRunner = new AgentRunner(new BackoffIdleStrategy(100, 10, 10_000, 1_000_000), Throwable::printStackTrace,
                null, ops);
        AgentRunner.startOnThread(opsRunner);
        metrics.gauge("clob_last_sequence", pipeline::lastSequence);
        metrics.gauge("clob_md_publishes", marketData::publishes);
        metrics.gauge("clob_replication_connected", () -> replication != null && replication.connected() ? 1 : 0);
        this.admin = new AdminHttp(cfg, core.refData, pipeline::submit, metrics, () -> "PRIMARY seq=" + pipeline.lastSequence(),
                null);
    }

    public int fixPort() {
        return gateway.port();
    }

    public int adminPort() {
        return admin.port();
    }

    public int replicationPort() {
        return replication == null ? -1 : replication.port();
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    public Metrics metrics() {
        return core.metrics;
    }

    public RefData refData() {
        return core.refData;
    }

    @Override
    public void close() throws IOException {
        admin.close();
        gatewayRunner.close();
        gateway.close();
        pipeline.close();
        opsRunner.close();
        ops.close();
        if (replication != null) {
            replication.close();
        }
        journal.close();
    }

    NodeConfig config() {
        return cfg;
    }
}
