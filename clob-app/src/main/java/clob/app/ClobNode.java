package clob.app;

import clob.credit.CreditEngine;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.fix.FixGateway;
import clob.fix.FixGatewayConfig;
import clob.fix.OrderRegistry;
import clob.marketdata.MarketDataService;
import clob.marketdata.UdpPublisher;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.Clock;
import clob.sequencer.EngineDispatcher;
import clob.sequencer.FanOutSink;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.JournalManifest;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import clob.sequencer.Wire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

/**
 * A primary CLOB node: recovers by replaying its journal, then runs the pipeline (sequencer, engine),
 * market data, FIX gateway, ops (drop copy, metrics), the admin/metrics HTTP API, and optionally
 * replication to a warm standby.
 *
 * <p>Lifecycle: STARTING, READY, then DRAINING and STOPPED, or FAILED from any running state. A fatal
 * error anywhere fails the node: ingress is refused, readiness drops and the process should be
 * replaced. See {@link #close()} for the order of a staged shutdown.
 */
public final class ClobNode implements AutoCloseable {
    public enum State { STARTING, READY, DRAINING, FAILED, STOPPED }

    private static final long STAGE_TIMEOUT_NANOS = 10_000_000_000L;

    /** Engine state plus output plumbing; built by recovery and shared with a standby that is promoted. */
    static final class Core {
        RefData refData;
        JournalManifest manifest;
        MatchingEngine engine;
        EngineDispatcher dispatcher;
        OutputRing gatewayRing;
        OutputRing opsRing;
        SwitchableSink sink;
        OrderRegistry registry;
        Metrics metrics;
        long lastSequence;
        long lastTime;
        SnapshotStore.Snapshot snapshot;
    }

    static EngineConfig engineConfig(NodeConfig cfg) {
        EngineConfig d = EngineConfig.defaults();
        return new EngineConfig(d.initialOrders(), d.maxLiveOrders(), d.initialLevels(), d.initialLevelsPerSide(),
                d.initialTimers(), cfg.randomSeed());
    }

    static FixGatewayConfig gatewayConfig(NodeConfig cfg) {
        return new FixGatewayConfig(cfg.fixCompId(), cfg.fixBindHost(), cfg.fixPort(), 1, cfg.fixSessions(), 10_000,
                cfg.dataDir().resolve("fix"));
    }

    /** The manifest this configuration requires of a journal. */
    static JournalManifest recoverManifest(NodeConfig cfg) throws IOException {
        EngineConfig engineConfig = engineConfig(cfg);
        return new JournalManifest(Wire.SCHEMA_VERSION, JournalManifest.digest(Files.readAllLines(cfg.refDataFile())),
                cfg.randomSeed(), cfg.maxAccounts(), engineConfig.initialOrders() + "/" + engineConfig.maxLiveOrders());
    }

    /** Fails on configuration that could only produce wrong behaviour at run time. */
    static void validate(NodeConfig cfg, RefData refData) {
        if (refData.maxReferencedAccount() > cfg.maxAccounts()) {
            throw new IllegalArgumentException("Reference data names account " + refData.maxReferencedAccount()
                    + " but accounts.max is " + cfg.maxAccounts());
        }
        for (Instrument i : refData.instruments()) {
            if (i.mdIntervalNanos() % cfg.mdIntervalNanos() != 0) {
                throw new IllegalArgumentException("Instrument " + i.key() + " market data interval " + i.mdIntervalNanos()
                        + "ns is not a multiple of the global market data tick " + cfg.mdIntervalNanos() + "ns");
            }
        }
    }

    static Core recover(NodeConfig cfg, OrderRegistry.Mode registryMode) {
        try {
            Core c = new Core();
            List<String> lines = Files.readAllLines(cfg.refDataFile());
            c.refData = RefDataLoader.parse(lines);
            validate(cfg, c.refData);
            EngineConfig engineConfig = engineConfig(cfg);
            c.manifest = recoverManifest(cfg);
            c.manifest.verifyOrCreate(cfg.journalDir());
            c.metrics = new Metrics();
            c.gatewayRing = new OutputRing(1 << 22);
            c.opsRing = new OutputRing(1 << 22);
            c.registry = new OrderRegistry(gatewayConfig(cfg), c.refData, System::currentTimeMillis, registryMode);
            c.sink = new SwitchableSink(new FanOutSink(c.gatewayRing, c.opsRing),
                    (b, o, l) -> c.registry.onReplayedOutput(b, o));
            c.engine = new MatchingEngine(c.refData, engineConfig, new CreditEngine(cfg.maxAccounts(), c.refData),
                    new OutputEncoder(c.sink));
            c.dispatcher = new EngineDispatcher(c.engine, (s, t, ty, r) -> { });
            SnapshotStore.Snapshot snapshot = SnapshotStore.latest(SnapshotJob.directory(cfg), c.manifest.fingerprint());
            if (snapshot != null) {
                SnapshotJob.restore(snapshot, c.engine, c.registry);
                c.lastSequence = snapshot.sequence();
                c.lastTime = snapshot.time();
                c.snapshot = snapshot;
            }
            if (Files.isDirectory(cfg.journalDir())) {
                Journal.replayAfter(cfg.journalDir(), c.lastSequence, (b, o, l) -> {
                    c.registry.onReplayedInput(b, o);
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
    private volatile State state = State.STARTING;
    private volatile Throwable fatal;
    private Pipeline pipeline;
    private MarketDataService marketData;
    private ReplicationSender replication;
    private UdpPublisher udp;
    private FixGateway gateway;
    private AgentRunner gatewayRunner;
    private OpsAgent ops;
    private AgentRunner opsRunner;
    private AdminHttp admin;
    private JournalFlusher flusher;
    private SnapshotJob snapshots;

    /** Cold start (or restart): replay the journal, then open for business. */
    public static ClobNode startPrimary(NodeConfig cfg) throws IOException {
        Core core = recover(cfg, OrderRegistry.Mode.CATCH_UP);
        Journal journal = Journal.open(cfg.journalDir(), 64 << 20);
        try {
            return new ClobNode(cfg, core, journal);
        } catch (IOException | RuntimeException e) {
            journal.close();
            throw e;
        }
    }

    ClobNode(NodeConfig cfg, Core core, Journal journal) throws IOException {
        this.cfg = cfg;
        this.core = core;
        this.journal = journal;
        try {
            start();
        } catch (IOException | RuntimeException e) {
            state = State.FAILED;
            stopQuietly();
            throw e;
        }
        state = State.READY;
    }

    /** Acquires everything in dependency order; a failure part way is undone by {@link #stopQuietly()}. */
    private void start() throws IOException {
        Metrics metrics = core.metrics;
        pipeline = new Pipeline(core.engine, journal, Clock.system(), (s, t, ty, r) -> {
            metrics.inc("clob_admin_refused_total", 1);
            System.err.println("refused input seq=" + s + " type=" + ty + ": " + r);
        }, core.lastSequence, core.lastTime, 1 << 20, this::idleStrategy);
        pipeline.onFailure(t -> fail("pipeline", t));
        marketData = new MarketDataService(core.refData, engineConfig(cfg), cfg.maxAccounts(), cfg.mdPublishThreads());
        long replicaFrom = 0;
        if (core.snapshot != null) {
            SnapshotJob.restoreEngine(core.snapshot, marketData.replica());
            replicaFrom = core.snapshot.sequence();
        }
        Journal.replayAfter(cfg.journalDir(), replicaFrom, marketData); // bring the market data replica up to the same point
        pipeline.addFollower(marketData);
        pipeline.enableMarketDataTicks(cfg.mdIntervalNanos());
        if (cfg.endOfDayTime() != null) {
            pipeline.enableEndOfDay(cfg.endOfDayTime(), cfg.endOfDayZone());
        }
        gateway = new FixGateway(gatewayConfig(cfg), core.refData, pipeline, core.gatewayRing, marketData,
                System::currentTimeMillis, core.registry);
        if (cfg.mdUdpEnabled()) {
            udp = new UdpPublisher();
            gateway.enableUdp(udp, cfg.mdUdpRefreshCycles());
        }
        snapshots = new SnapshotJob(cfg, core.refData, core.manifest, metrics);
        ops = new OpsAgent(core.opsRing, metrics, cfg.dataDir(), () -> snapshots.trigger(false));
        admin = new AdminHttp(cfg, core.refData, pipeline::submit, metrics,
                () -> "PRIMARY state=" + state + " seq=" + pipeline.lastSequence(), this::live, this::ready, null,
                () -> snapshots.trigger(true));
        if (cfg.replicationPort() >= 0) {
            replication = new ReplicationSender(cfg.replicationBindHost(), cfg.replicationPort(), cfg.journalDir(),
                    core.manifest.fingerprint(), cfg.replicationDeadlineMillis());
        }
        core.sink.enable();
        pipeline.start();
        gatewayRunner = run(gateway, "fix-gateway");
        opsRunner = run(ops, "ops");
        if (cfg.journalFlushMillis() > 0) {
            flusher = new JournalFlusher(journal, cfg.journalFlushMillis(), t -> fail("journal-flusher", t));
        }
        metrics.gauge("clob_last_sequence", pipeline::lastSequence);
        metrics.gauge("clob_engine_processed_sequence", pipeline::processedSequence);
        metrics.gauge("clob_follower_processed_sequence", pipeline::slowestFollowerSequence);
        metrics.gauge("clob_inbound_queued_bytes", pipeline::inboundQueued);
        metrics.gauge("clob_gateway_output_queued_bytes", core.gatewayRing::size);
        metrics.gauge("clob_ops_output_queued_bytes", core.opsRing::size);
        metrics.gauge("clob_gateway_backlog", gateway::backlogSize);
        metrics.gauge("clob_md_publishes", marketData::publishes);
        metrics.gauge("clob_md_cycle_overruns", marketData::overruns);
        metrics.gauge("clob_md_dropped_snapshots", gateway::marketDataDropped);
        metrics.gauge("clob_md_max_tick_lateness_nanos", pipeline::maxMarketDataLatenessNanos);
        metrics.gauge("clob_node_ready", () -> ready() ? 1 : 0);
        metrics.gauge("clob_replication_connected", () -> replication != null && replication.connected() ? 1 : 0);
        metrics.gauge("clob_replication_sent_sequence", () -> replication != null ? replication.sentSequence() : 0);
        metrics.gauge("clob_replication_lag", () -> replication != null && replication.connected()
                ? pipeline.lastSequence() - replication.sentSequence() : 0);
    }

    private IdleStrategy idleStrategy() {
        return cfg.busySpin() ? new BusySpinIdleStrategy() : Pipeline.backoff();
    }

    private AgentRunner run(Agent agent, String name) {
        AgentRunner runner = new AgentRunner(idleStrategy(),
                t -> {
                    if (!(t instanceof AgentTerminationException)) {
                        fail(name, t);
                    }
                }, null, new FatalGuard(agent));
        AgentRunner.startOnThread(runner);
        return runner;
    }

    /** Stops an agent at its first exception: running on after an unknown error could corrupt reporting. */
    private static final class FatalGuard implements Agent {
        private final Agent agent;
        private boolean failed;

        FatalGuard(Agent agent) {
            this.agent = agent;
        }

        @Override
        public int doWork() throws Exception {
            if (failed) {
                throw new AgentTerminationException();
            }
            try {
                return agent.doWork();
            } catch (Exception e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public String roleName() {
            return agent.roleName();
        }
    }

    private void fail(String component, Throwable t) {
        if (fatal == null) {
            fatal = t;
            System.err.println("FATAL in " + component + ": " + t);
            t.printStackTrace();
        }
        core.metrics.inc("clob_fatal_errors_total", 1);
        state = State.FAILED;
        pipeline.closeIngress();
        core.gatewayRing.abort(); // nothing may wait forever for a consumer that has stopped
        core.opsRing.abort();
    }

    /** Test hook: what any agent failure does. */
    void failForTest(Throwable t) {
        fail("test", t);
    }

    public State state() {
        return state;
    }

    public Throwable fatalError() {
        return fatal;
    }

    /** The process is working (a failed node should be restarted). */
    boolean live() {
        return state != State.FAILED && state != State.STOPPED;
    }

    /** The node should receive traffic: serving, with every consumer keeping up. */
    boolean ready() {
        return state == State.READY && pipeline.lastSequence() - pipeline.processedSequence() <= cfg.readyMaxLag()
                && pipeline.lastSequence() - pipeline.slowestFollowerSequence() <= cfg.readyMaxLag();
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

    boolean snapshotRunning() {
        return snapshots.isRunning();
    }

    public MarketDataService marketData() {
        return marketData;
    }

    public FixGateway gateway() {
        return gateway;
    }

    private boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + STAGE_TIMEOUT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0 || state == State.FAILED) {
                return false;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Staged shutdown: stop FIX ingress and submit cancel-on-disconnect while the pipeline still runs;
     * close ingress; drain sequencer, engine and followers to a watermark; let the gateway and ops consume
     * everything the engine produced; flush the journal; then close transports. Every stage is
     * time-limited, and a stuck or failed stage cannot stop later cleanup.
     */
    @Override
    public synchronized void close() throws IOException {
        if (state == State.STOPPED) {
            return;
        }
        boolean clean = state == State.READY;
        if (clean) {
            state = State.DRAINING;
        }
        List<Throwable> errors = new ArrayList<>();
        if (clean) {
            step(errors, () -> {
                gateway.stopIngress();
                if (!await(gateway::ingressStopped)) {
                    throw new IllegalStateException("gateway did not stop ingress in time");
                }
            });
            step(errors, () -> {
                pipeline.closeIngress();
                if (!pipeline.drain(STAGE_TIMEOUT_NANOS)) {
                    throw new IllegalStateException("pipeline did not drain in time");
                }
            });
            step(errors, () -> {
                if (!await(() -> core.gatewayRing.size() == 0 && core.opsRing.size() == 0)) {
                    throw new IllegalStateException("output consumers did not drain in time");
                }
            });
        }
        if (!errors.isEmpty() || !clean) {
            core.gatewayRing.abort(); // release an engine still waiting on a consumer we are about to stop
            core.opsRing.abort();
        }
        stop(errors);
        state = fatal != null ? State.FAILED : State.STOPPED;
        if (!errors.isEmpty()) {
            IOException e = new IOException("Shutdown completed with errors");
            errors.forEach(e::addSuppressed);
            throw e;
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private static void step(List<Throwable> errors, Step s) {
        try {
            s.run();
        } catch (Exception e) {
            errors.add(e);
        }
    }

    /** Stops whatever exists, in reverse order of use. */
    private void stop(List<Throwable> errors) {
        step(errors, () -> {
            if (admin != null) {
                admin.close();
            }
        });
        step(errors, () -> {
            if (gatewayRunner != null) {
                gatewayRunner.close();
            }
        });
        step(errors, () -> {
            if (gateway != null) {
                gateway.close();
            }
        });
        step(errors, () -> {
            if (pipeline != null && pipeline.started()) {
                pipeline.close(STAGE_TIMEOUT_NANOS);
            }
        });
        step(errors, () -> {
            if (opsRunner != null) {
                opsRunner.close();
            }
        });
        step(errors, () -> {
            if (ops != null) {
                ops.close();
            }
        });
        step(errors, () -> {
            if (marketData != null) {
                marketData.close();
            }
        });
        step(errors, () -> {
            if (udp != null) {
                udp.close();
            }
        });
        step(errors, () -> {
            if (replication != null) {
                replication.close();
            }
        });
        step(errors, () -> {
            if (flusher != null) {
                flusher.close();
            }
        });
        step(errors, journal::close);
    }

    private void stopQuietly() {
        List<Throwable> errors = new ArrayList<>();
        core.gatewayRing.abort();
        core.opsRing.abort();
        stop(errors);
    }

    NodeConfig config() {
        return cfg;
    }
}
