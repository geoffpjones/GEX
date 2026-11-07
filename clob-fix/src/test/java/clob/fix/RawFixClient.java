package clob.fix;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

/** A minimal blocking FIX client for session-layer tests, built on the gateway's own codec. */
final class RawFixClient implements AutoCloseable {
    private final SocketChannel ch;
    private final String sender;
    private final FixEncoder enc = new FixEncoder();
    private final FixParser parser = new FixParser();
    private final ByteBuffer in = ByteBuffer.allocate(1 << 16);
    long nextSeq = 1;

    RawFixClient(int port, String sender) throws IOException {
        ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        ch.socket().setSoTimeout(3000);
        this.sender = sender;
    }

    /** Sends a message; fields are tag, value pairs. */
    void send(String msgType, Object... fields) throws IOException {
        sendWithSeq(nextSeq++, msgType, fields);
    }

    void sendWithSeq(long seq, String msgType, Object... fields) throws IOException {
        enc.begin(msgType, sender, GatewayHarness.COMP_ID, seq, System.currentTimeMillis(), false, 0);
        for (int i = 0; i < fields.length; i += 2) {
            enc.add((Integer) fields[i], String.valueOf(fields[i + 1]));
        }
        int len = enc.finish();
        ch.write(ByteBuffer.wrap(enc.bytes(), 0, len));
    }

    void logon(String user, String password) throws IOException {
        send("A", 98, 0, 108, 30, 141, "Y", 553, user, 554, password);
    }

    /** Next message as tag → value (last occurrence wins), or null on timeout/close. */
    Map<Integer, String> read(long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ch.configureBlocking(false);
        while (System.currentTimeMillis() < deadline) {
            int len = FixParser.frame(in.array(), 0, in.position());
            if (len > 0) {
                String err = parser.parse(in.array(), 0, len);
                if (err != null) {
                    throw new IOException(err);
                }
                Map<Integer, String> m = new HashMap<>();
                for (int tag : new int[] {7, 11, 14, 16, 17, 31, 32, 34, 35, 36, 37, 39, 41, 43, 45, 58, 102, 103, 112,
                        122, 123, 150, 151, 372, 380, 434}) {
                    if (parser.has(tag)) {
                        m.put(tag, parser.str(tag));
                    }
                }
                in.flip().position(len);
                in.compact();
                return m;
            }
            if (ch.read(in) < 0) {
                return null;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    /** Reads until a message of {@code msgType} arrives. */
    Map<Integer, String> await(String msgType, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<Integer, String> m = read(deadline - System.currentTimeMillis());
            if (m == null) {
                return null;
            }
            if (msgType.equals(m.get(35))) {
                return m;
            }
        }
        return null;
    }

    boolean isClosed() throws IOException {
        ch.configureBlocking(false);
        return ch.read(ByteBuffer.allocate(1)) < 0;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
