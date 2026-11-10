package clob.app;

import clob.sequencer.InputCodec;
import clob.sequencer.InputHandler;
import clob.sequencer.JournalTailer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * Primary side of warm-standby replication. Each connected standby is served by tailing the journal on
 * this object's own thread, so a slow or dead standby cannot back-pressure the primary: it only falls
 * behind. A standby that accepts no bytes for the deadline is disconnected and must reconnect.
 *
 * <p>Handshake: the standby sends its last sequence (8 bytes) and the manifest fingerprint (64 ASCII
 * bytes); the primary answers 1, or 0 and closes if the histories are incompatible. Replication is
 * asynchronous: an acknowledged input may not have reached the standby yet (see docs/RUNBOOK.md).
 */
final class ReplicationSender implements AutoCloseable {
    private static final int HANDSHAKE_BYTES = 8 + 64;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 2_000;

    private final ServerSocketChannel server;
    private final Path journalDir;
    private final String fingerprint;
    private final long deadlineMillis;
    private final Thread acceptor;
    private final Thread sender;
    private final ByteBuffer frame = ByteBuffer.allocateDirect(4 + InputCodec.MAX_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    private final byte[] tmp = new byte[InputCodec.MAX_LENGTH];
    private volatile SocketChannel pending;
    private volatile long pendingFrom;
    private volatile SocketChannel active;
    private volatile boolean closed;
    private volatile long sentSequence;

    ReplicationSender(String bindHost, int port, Path journalDir, String fingerprint, long deadlineMillis) throws IOException {
        this.journalDir = journalDir;
        this.fingerprint = fingerprint;
        this.deadlineMillis = deadlineMillis;
        this.server = ServerSocketChannel.open().bind(new InetSocketAddress(bindHost, port));
        this.acceptor = new Thread(this::acceptLoop, "clob-replication-acceptor");
        this.sender = new Thread(this::sendLoop, "clob-replication-sender");
        acceptor.setDaemon(true);
        sender.setDaemon(true);
        acceptor.start();
        sender.start();
    }

    int port() {
        return server.socket().getLocalPort();
    }

    boolean connected() {
        return active != null;
    }

    long sentSequence() {
        return sentSequence;
    }

    private void acceptLoop() {
        while (!closed) {
            SocketChannel ch = null;
            try {
                ch = server.accept();
                ch.socket().setTcpNoDelay(true);
                ch.socket().setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                byte[] hello = ch.socket().getInputStream().readNBytes(HANDSHAKE_BYTES);
                if (hello.length != HANDSHAKE_BYTES) {
                    throw new IOException("standby closed before handshake");
                }
                ByteBuffer b = ByteBuffer.wrap(hello).order(ByteOrder.LITTLE_ENDIAN);
                long from = b.getLong();
                String theirs = new String(hello, 8, 64, StandardCharsets.US_ASCII);
                boolean compatible = fingerprint.equals(theirs);
                ch.socket().getOutputStream().write(compatible ? 1 : 0);
                if (!compatible) {
                    System.err.println("Refused a standby with an incompatible manifest");
                    ch.close();
                    continue;
                }
                ch.socket().setSoTimeout(0);
                ch.configureBlocking(false);
                pendingFrom = from;
                SocketChannel previous = pending;
                pending = ch;
                closeQuietly(previous);
            } catch (IOException e) {
                closeQuietly(ch);
                if (closed) {
                    return;
                }
            }
        }
    }

    private void sendLoop() {
        JournalTailer tailer = null;
        InputHandler send = this::send;
        while (!closed) {
            SocketChannel joining = pending;
            if (joining != null) {
                pending = null;
                closeQuietly(active); // the newest standby replaces any earlier connection
                active = joining;
                sentSequence = pendingFrom;
                tailer = new JournalTailer(journalDir, pendingFrom);
            }
            if (active == null || tailer == null) {
                pause();
                continue;
            }
            try {
                if (tailer.poll(send, 256) == 0) {
                    pause();
                }
            } catch (RuntimeException e) {
                System.err.println("Replication stopped: " + e);
                drop();
                tailer = null;
            }
        }
        closeQuietly(active);
    }

    private static void pause() {
        java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
    }

    private void send(DirectBuffer b, int o, int length) {
        SocketChannel ch = active;
        if (ch == null) {
            return;
        }
        b.getBytes(o, tmp, 0, length);
        frame.clear();
        frame.putInt(length).put(tmp, 0, length).flip();
        long lastProgress = System.nanoTime();
        try {
            while (frame.hasRemaining() && !closed) {
                if (ch.write(frame) > 0) {
                    lastProgress = System.nanoTime();
                } else if (System.nanoTime() - lastProgress > deadlineMillis * 1_000_000L) {
                    System.err.println("Disconnecting a standby that stopped reading");
                    drop();
                    return;
                } else {
                    pause();
                }
            }
            sentSequence = InputCodec.sequence(b, o);
        } catch (IOException e) {
            drop();
        }
    }

    private void drop() {
        closeQuietly(active);
        active = null;
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // dropping it anyway
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        closeQuietly(pending);
        try {
            sender.join(5_000);
            closeQuietly(active);
            acceptor.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
