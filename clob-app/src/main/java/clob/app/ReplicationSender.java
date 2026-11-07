package clob.app;

import clob.sequencer.InputCodec;
import clob.sequencer.InputHandler;
import clob.sequencer.Journal;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * Primary side of warm-standby replication, run as a pipeline follower: streams every stamped input
 * (exactly the journal) to one standby over TCP. A standby connects, sends the last sequence it has, is
 * caught up from the journal on disk, then follows live. Replication is asynchronous: an acknowledged
 * input may not have reached the standby yet (PLAN Q40). A slow standby back-pressures the primary
 * through the follower ring; a broken connection is dropped and the standby must reconnect.
 */
final class ReplicationSender implements InputHandler, AutoCloseable {
    private final ServerSocketChannel server;
    private final Path journalDir;
    private final Thread acceptor;
    private final ByteBuffer frame = ByteBuffer.allocateDirect(4 + InputCodec.MAX_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    private final byte[] tmp = new byte[InputCodec.MAX_LENGTH];
    private volatile SocketChannel pending;
    private volatile long pendingFrom;
    private SocketChannel active;
    private volatile boolean closed;

    ReplicationSender(int port, Path journalDir) throws IOException {
        this.journalDir = journalDir;
        this.server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", port));
        this.acceptor = new Thread(this::acceptLoop, "clob-replication-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.socket().getLocalPort();
    }

    boolean connected() {
        return active != null;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                SocketChannel ch = server.accept();
                ch.socket().setTcpNoDelay(true);
                ByteBuffer from = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                while (from.hasRemaining()) {
                    if (ch.read(from) < 0) {
                        throw new IOException("standby closed before handshake");
                    }
                }
                pendingFrom = from.getLong(0);
                pending = ch;
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    @Override
    public void onInput(DirectBuffer buffer, int offset, int length) {
        long seq = InputCodec.sequence(buffer, offset);
        SocketChannel joining = pending;
        if (joining != null) {
            pending = null;
            long from = pendingFrom;
            active = joining;
            // Catch up from the journal (which already holds this input), then continue live.
            Journal.replay(journalDir, (b, o, l) -> {
                long s = InputCodec.sequence(b, o);
                if (s > from && s < seq) {
                    send(b, o, l);
                }
            });
        }
        send(buffer, offset, length);
    }

    private void send(DirectBuffer b, int o, int length) {
        if (active == null) {
            return;
        }
        b.getBytes(o, tmp, 0, length);
        frame.clear();
        frame.putInt(length).put(tmp, 0, length).flip();
        try {
            while (frame.hasRemaining()) {
                active.write(frame);
            }
        } catch (IOException e) {
            try {
                active.close();
            } catch (IOException ignored) {
                // dropping it anyway
            }
            active = null;
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        if (active != null) {
            active.close();
        }
    }
}
