package clob.app;

import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Warm standby: replays its own journal, connects to the primary's replication port, and from then on
 * journals and applies every input the primary sequenced, so its engine state is identical (determinism).
 * Engine output is suppressed. {@link #promote()} stops following and starts a primary node on this
 * state, continuing the sequence. There is no automatic failure detection or fencing: promotion is an
 * operator (or external arbiter) decision, and the old primary must be stopped first (PLAN Q43).
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

    public StandbyNode(NodeConfig cfg) throws IOException {
        this.cfg = cfg;
        this.core = ClobNode.recover(cfg);
        this.lastSequence = core.lastSequence;
        this.journal = Journal.open(cfg.journalDir(), 64 << 20);
        this.receiver = new Thread(this::follow, "clob-standby");
        receiver.start();
        this.admin = new AdminHttp(cfg, core.refData, null, core.metrics,
                () -> (promoted != null ? "PROMOTED " : "STANDBY seq=") + lastSequence, this::promoteQuietly);
    }

    public long lastSequence() {
        return lastSequence;
    }

    public int adminPort() {
        return admin.port();
    }

    private void follow() {
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer body = ByteBuffer.allocate(InputCodec.MAX_LENGTH);
        while (!stopping) {
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(cfg.primaryHost(), cfg.primaryReplicationPort()))) {
                channel = ch;
                ByteBuffer hello = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, lastSequence);
                while (hello.hasRemaining()) {
                    ch.write(hello);
                }
                while (!stopping) {
                    len.clear();
                    readFully(ch, len);
                    int n = len.getInt(0);
                    body.clear().limit(n);
                    readFully(ch, body);
                    buf.putBytes(0, body.array(), 0, n);
                    long seq = InputCodec.sequence(buf, 0);
                    if (seq <= lastSequence) {
                        continue; // already have it
                    }
                    journal.append(buf, 0, n);
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
            }
        }
    }

    private static void readFully(SocketChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) {
            if (ch.read(b) < 0) {
                throw new IOException("primary closed the replication stream");
            }
        }
    }

    /** Stops following and starts serving as primary from the replicated state. */
    public synchronized ClobNode promote() throws IOException {
        if (promoted != null) {
            return promoted;
        }
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
        admin.close();
        journal.close();
    }
}
