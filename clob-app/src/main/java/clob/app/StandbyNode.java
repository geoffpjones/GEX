package clob.app;

import clob.fix.OrderRegistry;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Warm standby: replays its own journal, connects to the primary's replication port, and from then on
 * journals and applies every input the primary sequenced, so its engine state and order ownership are
 * identical (determinism). Engine output is suppressed. {@link #promote()} stops following and starts a
 * primary node on this state, continuing the sequence. There is no automatic failure detection or
 * fencing: promotion is an operator (or external arbiter) decision, and the old primary must be
 * stopped first. After promotion clients log on with ResetSeqNumFlag and can reconcile with
 * OrderStatusRequest; FIX sequence numbers are not replicated.
 */
public final class StandbyNode implements AutoCloseable {
    private final NodeConfig cfg;
    private final ClobNode.Core core;
    private final Journal journal;
    private final Thread receiver;
    private final AdminHttp admin;
    private volatile SocketChannel channel;
    private volatile boolean stopping;
    private volatile long lastSequence;
    private volatile ClobNode promoted;
    private volatile String failure;

    public StandbyNode(NodeConfig cfg) throws IOException {
        this.cfg = cfg;
        this.core = ClobNode.recover(cfg, OrderRegistry.Mode.SILENT);
        this.lastSequence = core.lastSequence;
        this.journal = Journal.open(cfg.journalDir(), 64 << 20);
        this.receiver = new Thread(this::follow, "clob-standby");
        try {
            this.admin = new AdminHttp(cfg, core.refData, null, core.metrics,
                    () -> (promoted != null ? "PROMOTED " : "STANDBY seq=") + lastSequence + (failure != null ? " FAILED: " + failure : ""),
                    () -> failure == null, () -> failure == null, this::promoteQuietly, null);
        } catch (IOException | RuntimeException e) {
            journal.close();
            throw e;
        }
        receiver.start();
    }

    public long lastSequence() {
        return lastSequence;
    }

    public int adminPort() {
        return admin.port();
    }

    /** Why this standby stopped following, or null. */
    public String failure() {
        return failure;
    }

    private void follow() {
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer body = ByteBuffer.allocate(InputCodec.MAX_LENGTH);
        while (!stopping && failure == null) {
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(cfg.primaryHost(), cfg.primaryReplicationPort()))) {
                channel = ch;
                handshake(ch);
                while (!stopping && failure == null) {
                    len.clear();
                    readFully(ch, len);
                    int n = len.getInt(0);
                    if (n < InputCodec.HEADER || n > InputCodec.MAX_LENGTH) {
                        failure = "replication frame of " + n + " bytes";
                        return;
                    }
                    body.clear().limit(n);
                    readFully(ch, body);
                    buf.putBytes(0, body.array(), 0, n);
                    long seq = InputCodec.sequence(buf, 0);
                    if (seq <= lastSequence) {
                        continue; // already have it
                    }
                    if (seq != lastSequence + 1) {
                        failure = "replication gap: received " + seq + " after " + lastSequence;
                        return;
                    }
                    journal.append(buf, 0, n);
                    core.registry.onReplayedInput(buf, 0);
                    core.dispatcher.onInput(buf, 0, n);
                    core.lastSequence = seq;
                    core.lastTime = InputCodec.time(buf, 0);
                    lastSequence = seq;
                }
            } catch (IOException e) {
                if (stopping) {
                    return;
                }
                try {
                    Thread.sleep(200); // primary unavailable: retry
                } catch (InterruptedException ie) {
                    return;
                }
            } catch (RuntimeException e) {
                failure = e.toString();
                return;
            }
        }
    }

    private void handshake(SocketChannel ch) throws IOException {
        ByteBuffer hello = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        hello.putLong(lastSequence).put(core.manifest.fingerprint().getBytes(StandardCharsets.US_ASCII)).flip();
        while (hello.hasRemaining()) {
            ch.write(hello);
        }
        ByteBuffer answer = ByteBuffer.allocate(1);
        readFully(ch, answer);
        if (answer.get(0) != 1) {
            failure = "the primary refused this standby: reference data, seed or schema differ";
        }
    }

    private static void readFully(SocketChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) {
            if (ch.read(b) < 0) {
                throw new IOException("primary closed the replication stream");
            }
        }
    }

    private void stopReceiver() throws IOException {
        stopping = true;
        SocketChannel ch = channel;
        if (ch != null) {
            ch.close();
        }
        try {
            receiver.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (receiver.isAlive()) {
            throw new IOException("standby receiver did not stop");
        }
    }

    /** Stops following and starts serving as primary from the replicated state. */
    public synchronized ClobNode promote() throws IOException {
        if (promoted != null) {
            return promoted;
        }
        if (failure != null) {
            throw new IOException("Cannot promote a failed standby: " + failure);
        }
        stopReceiver(); // the receiver must be gone: the new primary takes exclusive ownership of the state
        admin.close(); // the promoted node runs its own admin API
        promoted = new ClobNode(cfg, core, journal);
        return promoted;
    }

    private void promoteQuietly() {
        try {
            promote();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public ClobNode promoted() {
        return promoted;
    }

    @Override
    public void close() throws IOException {
        if (promoted != null) {
            promoted.close();
            return;
        }
        stopReceiver();
        admin.close();
        journal.close();
        core.registry.close();
    }
}
