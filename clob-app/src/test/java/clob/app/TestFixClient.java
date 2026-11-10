package clob.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** A small FIX 4.4 client, independent of the gateway's codec, for node-level tests. */
final class TestFixClient implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private final SocketChannel ch;
    private final String sender;
    private final StringBuilder in = new StringBuilder();
    long nextSeq = 1;

    TestFixClient(int port, String sender) throws IOException {
        ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        ch.configureBlocking(false);
        this.sender = sender;
    }

    void send(String type, Object... fields) throws IOException {
        StringBuilder body = new StringBuilder("35=" + type + "\u000149=" + sender + "\u000156=CLOB\u000134=" + nextSeq++
                + "\u000152=" + TIME.format(Instant.now()) + "\u0001");
        for (int i = 0; i < fields.length; i += 2) {
            body.append(fields[i]).append('=').append(fields[i + 1]).append('\u0001');
        }
        String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001" + body;
        int sum = 0;
        for (byte b : head.getBytes(StandardCharsets.US_ASCII)) {
            sum += b;
        }
        ByteBuffer out = ByteBuffer.wrap((head + String.format("10=%03d\u0001", sum & 0xFF)).getBytes(StandardCharsets.US_ASCII));
        while (out.hasRemaining()) {
            ch.write(out);
        }
    }

    void logon(String user, String password, boolean reset) throws IOException {
        if (reset) {
            send("A", 98, 0, 108, 30, 141, "Y", 553, user, 554, password);
        } else {
            send("A", 98, 0, 108, 30, 553, user, 554, password);
        }
    }

    void order(String clOrdId, int account, int side, String price) throws IOException {
        send("D", 11, clOrdId, 1, account, 55, "EUR/USD", 54, side, 38, 1000, 40, 2, 59, 1, 44, price);
    }

    private Map<String, String> next() throws IOException {
        int end = in.indexOf("\u000110=");
        if (end < 0 || in.length() < end + 8) {
            return null;
        }
        Map<String, String> m = new HashMap<>();
        for (String field : in.substring(0, end + 7).split("\u0001")) {
            int eq = field.indexOf('=');
            m.put(field.substring(0, eq), field.substring(eq + 1));
        }
        in.delete(0, end + 8);
        return m;
    }

    /** The next message of this type, or null on timeout or close. */
    Map<String, String> await(String type, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ByteBuffer buf = ByteBuffer.allocate(1 << 16);
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> m = next();
            if (m != null) {
                if (type.equals(m.get("35"))) {
                    return m;
                }
                continue;
            }
            buf.clear();
            int n = ch.read(buf);
            if (n < 0) {
                return null;
            }
            if (n == 0) {
                Thread.sleep(1);
            } else {
                in.append(new String(buf.array(), 0, n, StandardCharsets.US_ASCII));
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
