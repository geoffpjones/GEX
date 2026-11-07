package clob.fix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** State of one FIX connection (acceptor side). Owned by the gateway thread. */
final class FixSession {
    final SocketChannel channel;
    final long connectedMillis;
    final ByteBuffer in = ByteBuffer.allocate(1 << 16);
    final ByteBuffer out = ByteBuffer.allocate(1 << 20);

    FixSessionConfig config;
    boolean loggedOn;
    boolean closing;
    long expectedIn = 1;
    long nextOut = 1;
    long heartbeatMillis = 30_000;
    long lastInMillis;
    long lastOutMillis;
    String pendingTestRequest;
    long testRequestSentMillis;
    boolean resendRequested;

    /** Outbound application messages by sequence number, for ResendRequest: msgType + body fields. */
    final ArrayList<String> sentTypes = new ArrayList<>();
    final ArrayList<byte[]> sentBodies = new ArrayList<>();
    final ArrayList<Long> sentMillis = new ArrayList<>();

    /** MDReqID → market data subscription id. */
    final Map<String, Long> mdRequests = new HashMap<>();

    /** ClOrdID (orders and their cancel/replace requests) → engine order id. */
    final Map<String, Long> clOrdIds = new HashMap<>();

    double tokens;
    long tokensAtMillis;

    FixSession(SocketChannel channel, long nowMillis) {
        this.channel = channel;
        this.connectedMillis = nowMillis;
        this.lastInMillis = nowMillis;
        this.lastOutMillis = nowMillis;
        this.tokensAtMillis = nowMillis;
    }

    void resetSequences() {
        expectedIn = 1;
        nextOut = 1;
        sentTypes.clear();
        sentBodies.clear();
        sentMillis.clear();
        resendRequested = false;
    }

    /** Records an outbound message for possible resend (admin messages are recorded as null). */
    void store(long seq, String msgType, byte[] body, long millis) {
        while (sentTypes.size() < seq - 1) {
            sentTypes.add(null);
            sentBodies.add(null);
            sentMillis.add(0L);
        }
        sentTypes.add(msgType);
        sentBodies.add(body);
        sentMillis.add(millis);
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
