package clob.app;

import clob.engine.CancelReason;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputReader;
import clob.sequencer.OutputRing;
import clob.sequencer.Printers;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.Agent;

/**
 * Consumes engine output off the hot path: writes the drop copy (every event, human-readable,
 * {@code dropcopy.log}) and the trade report ({@code trades.csv}), and updates metrics. The binary
 * journal plus deterministic replay is the authoritative audit trail; these files are the readable copy.
 */
public final class OpsAgent implements Agent, AutoCloseable {
    private final OutputRing ring;
    private final Metrics metrics;
    private final OutputReader e = new OutputReader();
    private final BufferedWriter dropCopy;
    private final BufferedWriter trades;
    private int sinceFlush;
    private final Runnable onEndOfDay;

    public OpsAgent(OutputRing ring, Metrics metrics, Path dataDir) {
        this(ring, metrics, dataDir, () -> { });
    }

    /** {@code onEndOfDay} runs on this agent's thread at each end-of-day cut, and must not block. */
    public OpsAgent(OutputRing ring, Metrics metrics, Path dataDir, Runnable onEndOfDay) {
        this.onEndOfDay = onEndOfDay;
        this.ring = ring;
        this.metrics = metrics;
        try {
            Files.createDirectories(dataDir);
            dropCopy = Files.newBufferedWriter(dataDir.resolve("dropcopy.log"), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            Path tradeFile = dataDir.resolve("trades.csv");
            boolean fresh = !Files.exists(tradeFile);
            trades = Files.newBufferedWriter(tradeFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                trades.write("time,tradeId,instrumentId,aggressorOrderId,passiveOrderId,aggressorSide,price,priceDecimals,qty\n");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public int doWork() throws IOException {
        int n = ring.poll((type, buffer, index, length) -> {
            e.read(buffer, index);
            onEvent();
        }, 1024);
        sinceFlush += n;
        if (n == 0 && sinceFlush > 0) {
            dropCopy.flush();
            trades.flush();
            sinceFlush = 0;
        }
        return n;
    }

    private void onEvent() {
        try {
            dropCopy.write(Printers.output(e));
            dropCopy.write('\n');
            switch (e.type) {
                case OutputEncoder.ACCEPTED -> metrics.inc("clob_orders_accepted_total", 1);
                case OutputEncoder.REJECTED -> metrics.inc("clob_orders_rejected_total", 1);
                case OutputEncoder.TRADE -> {
                    metrics.inc("clob_trades_total", 1);
                    metrics.inc("clob_traded_qty_total", e.qty);
                    trades.write(e.time + "," + e.tradeId + "," + e.instrumentId + "," + e.aggressorOrderId + ","
                            + e.passiveOrderId + "," + (e.side == 0 ? "BUY" : "SELL") + "," + e.price + ","
                            + e.priceDecimals + "," + e.qty + "\n");
                }
                case OutputEncoder.CANCELLED -> metrics.inc(
                        "clob_cancels_total{reason=\"" + clob.sequencer.Wire.CANCEL_REASONS.of(e.reason) + "\"}", 1);
                case OutputEncoder.RACE_STARTED -> metrics.inc("clob_races_total", 1);
                case OutputEncoder.SESSION_STATE -> metrics.inc("clob_session_changes_total", 1);
                case OutputEncoder.END_OF_DAY -> onEndOfDay.run();
                case OutputEncoder.CREDIT_LIMIT_BREACHED -> {
                    metrics.inc("clob_credit_limit_breaches_total", 1);
                    System.err.println("ALERT credit line " + e.lineId + " over limit: usageE8=" + e.usageE8
                            + " limitE8=" + e.limitE8);
                }
                default -> { }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void close() throws IOException {
        dropCopy.close();
        trades.close();
    }

    @Override
    public String roleName() {
        return "clob-ops";
    }
}
