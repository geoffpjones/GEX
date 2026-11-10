package clob.app;

import clob.credit.CreditEngine;
import clob.engine.MatchingEngine;
import clob.fix.FixGatewayConfig;
import clob.fix.OrderRegistry;
import clob.refdata.RefData;
import clob.sequencer.EngineDispatcher;
import clob.sequencer.InputCodec;
import clob.sequencer.JournalManifest;
import clob.sequencer.JournalTailer;
import clob.sequencer.OutputEncoder;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes snapshots without touching the live engine: a throwaway replica loads the latest snapshot, tails
 * the journal, and writes a new snapshot at every END_OF_DAY input it passes (plus at the tail, when an
 * operator asks). Because engine and order ownership are applied on this one thread, both are always
 * consistent as of the same sequence. The live path pays nothing but the page-cache reads.
 */
final class SnapshotJob {
    private final NodeConfig cfg;
    private final RefData refData;
    private final JournalManifest manifest;
    private final Metrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    SnapshotJob(NodeConfig cfg, RefData refData, JournalManifest manifest, Metrics metrics) {
        this.cfg = cfg;
        this.refData = refData;
        this.manifest = manifest;
        this.metrics = metrics;
    }

    static Path directory(NodeConfig cfg) {
        return cfg.dataDir().resolve("snapshots");
    }

    /** Starts a background run unless one is already going. {@code atTail} also snapshots where the journal ends. */
    void trigger(boolean atTail) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                run(atTail);
                metrics.inc("clob_snapshots_total", 1);
            } catch (IOException | RuntimeException e) {
                metrics.inc("clob_snapshot_failures_total", 1);
                System.err.println("Snapshot failed: " + e);
            } finally {
                running.set(false);
            }
        }, "clob-snapshot");
        t.setDaemon(true);
        t.start();
    }

    boolean isRunning() {
        return running.get();
    }

    private void run(boolean atTail) throws IOException {
        FixGatewayConfig memoryOnly = new FixGatewayConfig(cfg.fixCompId(), cfg.fixBindHost(), 0, 1, cfg.fixSessions(), 10_000, null);
        OrderRegistry registry = new OrderRegistry(memoryOnly, refData, System::currentTimeMillis, OrderRegistry.Mode.SILENT);
        MatchingEngine engine = new MatchingEngine(refData, ClobNode.engineConfig(cfg), new CreditEngine(cfg.maxAccounts(), refData),
                new OutputEncoder((b, o, l) -> registry.onReplayedOutput(b, o)));
        EngineDispatcher dispatcher = new EngineDispatcher(engine, (s, t, ty, r) -> { });
        long[] position = {0, 0};
        SnapshotStore.Snapshot base = SnapshotStore.latest(directory(cfg), manifest.fingerprint());
        if (base != null) {
            restore(base, engine, registry);
            position[0] = base.sequence();
            position[1] = base.time();
        }
        JournalTailer tailer = new JournalTailer(cfg.journalDir(), position[0]);
        long start = position[0];
        boolean[] wroteLast = {false};
        int n;
        do {
            n = tailer.poll((b, o, l) -> {
                registry.onReplayedInput(b, o);
                dispatcher.onInput(b, o, l);
                position[0] = InputCodec.sequence(b, o);
                position[1] = InputCodec.time(b, o);
                wroteLast[0] = false;
                if (InputCodec.type(b, o) == InputCodec.END_OF_DAY) {
                    write(engine, registry, position[0], position[1]);
                    wroteLast[0] = true;
                }
            }, 4096);
        } while (n > 0);
        if (atTail && position[0] > start && !wroteLast[0]) {
            write(engine, registry, position[0], position[1]);
        }
        registry.close();
    }

    private void write(MatchingEngine engine, OrderRegistry registry, long sequence, long time) {
        try {
            SnapshotStore.write(directory(cfg), manifest.fingerprint(), sequence, time, out -> {
                engine.save(out);
                registry.save(out);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads engine and registry state from a snapshot. */
    static void restore(SnapshotStore.Snapshot snapshot, MatchingEngine engine, OrderRegistry registry) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot.payload()))) {
            engine.restore(in);
            registry.restore(in);
        }
    }

    /** Loads only the engine state (the market data replica). */
    static void restoreEngine(SnapshotStore.Snapshot snapshot, MatchingEngine engine) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot.payload()))) {
            engine.restore(in);
        }
    }
}
