package clob.app;

import clob.common.Side;
import clob.engine.NewOrderCommand;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;

final class AppFixture {
    static final String TOKEN = "t0ken";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static NodeConfig config(Path dir, String name, int replicationPort, int primaryReplicationPort) throws IOException {
        return config(dir, name, replicationPort, primaryReplicationPort, new Properties());
    }

    static NodeConfig config(Path dir, String name, int replicationPort, int primaryReplicationPort, Properties extra)
            throws IOException {
        Path refData = dir.resolve("refdata.txt");
        if (!Files.exists(refData)) {
            Files.writeString(refData, """
                    instrument 0 EUR/USD SPOT precision=0 priceIncrement=1 minQty=1000 qtyIncrement=1000 maxQty=1000000 mdIntervalMs=5 minTtlMicros=0
                    entitle 1 1
                    entitle 2 2
                    entitle 3 3
                    fixing 0 TEST 16:00 UTC 15:55
                    """);
        }
        Properties p = new Properties();
        p.setProperty("refdata.file", "refdata.txt");
        p.setProperty("journal.dir", name + "/journal");
        p.setProperty("data.dir", name + "/data");
        p.setProperty("accounts.max", "8");
        p.setProperty("fix.port", "0");
        p.setProperty("admin.port", "0");
        p.setProperty("admin.token", TOKEN);
        p.setProperty("replication.port", String.valueOf(replicationPort));
        p.setProperty("replication.primaryPort", String.valueOf(primaryReplicationPort));
        p.putAll(extra);
        return NodeConfig.from(p, dir);
    }

    static int post(int port, String pathAndQuery, String token) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            r.header("X-Admin-Token", token);
        }
        return HTTP.send(r.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static String get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Credit, rates and value dates for accounts 1..3 trading each other, via the admin API. */
    static void setUpCredit(int adminPort) throws Exception {
        for (int a = 1; a <= 3; a++) {
            post(adminPort, "/admin/credit/line?line=" + a + "&giver=" + a + "&type=UNLIMITED", TOKEN);
            for (int b = 1; b <= 3; b++) {
                if (a != b) {
                    post(adminPort, "/admin/credit/relationship?from=" + a + "&to=" + b + "&line=" + a, TOKEN);
                }
            }
        }
        post(adminPort, "/admin/usdrate?ccy=EUR&rate=1.1", TOKEN);
        post(adminPort, "/admin/valuedates?instrument=0&near=2026-11-10", TOKEN);
    }

    static void order(clob.sequencer.Pipeline p, long id, int account, Side side, long price, long qty, TimeInForce tif) {
        NewOrderCommand c = new NewOrderCommand();
        c.orderId = id;
        c.instrumentId = 0;
        c.accountId = account;
        c.userId = account;
        c.side = side;
        c.type = OrderType.LIMIT;
        c.tif = tif;
        c.price = price;
        c.qty = qty;
        UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        int len = InputCodec.newOrder(b, 0, c);
        while (!p.submit(b, 0, len)) {
            Thread.onSpinWait();
        }
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    static String get0(int port) {
        try {
            return get(port, "/metrics");
        } catch (Exception e) {
            return "";
        }
    }

    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<byte[]> journal(Path dir) {
        List<byte[]> out = new ArrayList<>();
        Journal.replay(dir, (b, o, l) -> {
            byte[] m = new byte[l];
            b.getBytes(o, m);
            out.add(m);
        });
        return out;
    }
}
