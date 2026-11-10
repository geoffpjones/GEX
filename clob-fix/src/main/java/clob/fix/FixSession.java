package clob.fix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

/** One TCP connection (acceptor side). Owned by the gateway thread. Logical state lives in {@link LogicalSession}. */
final class FixSession {
    final SocketChannel channel;
    final long connectedMillis;
    final ByteBuffer in = ByteBuffer.allocate(1 << 16);
    final ByteBuffer out = ByteBuffer.allocate(1 << 20);

    LogicalSession logical;
    FixSessionConfig config;
    boolean loggedOn;
    boolean closing;
    /** Bytes received but not yet parsed, because the gateway had to stop reading. */
    boolean unparsed;
    /** Cancel-on-disconnect has been submitted for this connection (at shutdown, ahead of the close). */
    boolean disconnectCancelSent;
    long heartbeatMillis = 30_000;
    long lastInMillis;
    long lastOutMillis;
    String pendingTestRequest;
    long testRequestSentMillis;
    boolean resendRequested;

    /** MDReqID → market data subscription id. */
    final Map<String, Long> mdRequests = new HashMap<>();

    double tokens;
    long tokensAtMillis;

    FixSession(SocketChannel channel, long nowMillis) {
        this.channel = channel;
        this.connectedMillis = nowMillis;
        this.lastInMillis = nowMillis;
        this.lastOutMillis = nowMillis;
        this.tokensAtMillis = nowMillis;
    }

    /** Token-bucket throttle. True if the message may proceed. */
    boolean admit(long nowMillis) {
        int rate = config == null ? 0 : config.maxMessagesPerSecond();
        if (rate <= 0) {
            return true;
        }
        tokens = Math.min(rate, tokens + (nowMillis - tokensAtMillis) * rate / 1000.0);
        tokensAtMillis = nowMillis;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    void queue(byte[] bytes, int length) {
        if (out.remaining() < length) {
            closing = true; // slow consumer: drop the connection rather than buffer without bound
            return;
        }
        out.put(bytes, 0, length);
    }

    /** Writes as much queued output as the socket takes. */
    void flush() throws IOException {
        if (out.position() == 0) {
            return;
        }
        out.flip();
        channel.write(out);
        out.compact();
    }
}
