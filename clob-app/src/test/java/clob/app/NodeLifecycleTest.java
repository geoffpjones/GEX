package clob.app;

import static clob.app.AppFixture.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.JournalManifest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identity across restart and promotion, shutdown, readiness, replication limits and configuration binding. */
class NodeLifecycleTest {
    @TempDir
    Path dir;

    static Properties fix(boolean cancelOnDisconnect) {
        Properties p = new Properties();
        p.setProperty("fix.allowPlaintextCredentials", "true");
        p.setProperty("fix.session.1", "C1,u1,p1,1," + cancelOnDisconnect + ",0");
        p.setProperty("fix.session.2", "C2,u2,p2,2,false,0");
        return p;
    }

    static int status(int port, String path) throws Exception {
        return AppFixture.HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static TestFixClient connect(ClobNode node, String sender, String user, String password, boolean reset) throws Exception {
        TestFixClient c = new TestFixClient(node.fixPort(), sender);
        c.logon(user, password, reset);
        assertNotNull(c.await("A", 5000), "logon");
        return c;
    }

    private static Map<String, String> report(TestFixClient c, String clOrdId) throws Exception {
        Map<String, String> m = c.await("8", 5000);
        assertNotNull(m, "report for " + clOrdId);
        assertEquals(clOrdId, m.get("11"));
        return m;
    }

    @Test
    void restartKeepsOrderControlSequenceNumbersAndUniqueIds() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "p", -1, -1, fix(false));
        String firstId;
        long resumeSeq;
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            node.pipeline().drain();
            TestFixClient c = connect(node, "C1", "u1", "p1", true);
            c.order("LIVE", 1, 1, "100");
            firstId = report(c, "LIVE").get("37");
            resumeSeq = c.nextSeq;
            c.close();
            node.pipeline().drain();
        }
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            TestFixClient c = new TestFixClient(node.fixPort(), "C1");
            c.nextSeq = resumeSeq;
            c.logon("u1", "p1", false);
            assertNotNull(c.await("A", 5000));
            c.order("NEW", 1, 1, "99");
            String secondId = report(c, "NEW").get("37");
            assertNotEquals(firstId, secondId, "a new order after restart must not reuse an id");
            assertTrue(Long.parseLong(secondId) > Long.parseLong(firstId));
            c.order("LIVE", 1, 1, "98");
            assertEquals("Duplicate ClOrdID", report(c, "LIVE").get("58"), "ClOrdID uniqueness survives the restart");
            c.send("F", 11, "CXL", 41, "LIVE", 55, "EUR/USD", 54, 1);
            Map<String, String> cancelled = report(c, "CXL");
            assertEquals("4", cancelled.get("150"));
            assertEquals(firstId, cancelled.get("37"));
            c.close();
        }
    }

    @Test
    void aLostSessionStoreDoesNotReplayHistoryAtTheClient() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "lost", -1, -1, fix(false));
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            TestFixClient c = connect(node, "C1", "u1", "p1", true);
            c.order("LIVE", 1, 1, "100");
            report(c, "LIVE");
            c.close();
            node.pipeline().drain();
        }
        try (var files = Files.list(cfg.dataDir().resolve("fix"))) {
            for (Path f : files.toList()) {
                Files.delete(f);
            }
        }
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            TestFixClient c = new TestFixClient(node.fixPort(), "C1");
            c.logon("u1", "p1", false);
            assertEquals("1", c.await("A", 5000).get("34"), "no reports were generated while recovering");
            c.send("F", 11, "CXL", 41, "LIVE", 55, "EUR/USD", 54, 1);
            assertEquals("4", report(c, "CXL").get("150"), "the journal still gave back ownership");
            c.close();
        }
    }

    @Test
    void pendingFixingMatchIsReportedAfterRestart() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "fx", -1, -1, fix(false));
        long fixingAt;
        long resume1;
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
            long now = System.currentTimeMillis() * 1_000_000L;
            fixingAt = now + 400_000_000L;
            while (!node.pipeline().submit(b, 0, InputCodec.fixingSchedule(b, 0, 0, now + 300_000_000L, fixingAt))) {
                Thread.onSpinWait();
            }
            node.pipeline().drain();
            TestFixClient c1 = connect(node, "C1", "u1", "p1", true);
            TestFixClient c2 = connect(node, "C2", "u2", "p2", true);
            c1.send("D", 11, "F1", 1, 1, 55, "EUR/USD", 54, 1, 38, 1000, 20010, "FIXING", 20012, 0, 59, 1);
            report(c1, "F1");
            c2.send("D", 11, "F2", 1, 2, 55, "EUR/USD", 54, 2, 38, 1000, 20010, "FIXING", 20012, 0, 59, 1);
            report(c2, "F2");
            assertNotNull(c1.await("8", 5000), "match notice");
            resume1 = c1.nextSeq;
            c1.close();
            c2.close();
            node.pipeline().drain();
        }
        while (System.currentTimeMillis() * 1_000_000L < fixingAt + 10_000_000L) {
            Thread.sleep(10);
        }
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            TestFixClient c = new TestFixClient(node.fixPort(), "C1");
            c.nextSeq = resume1;
            c.logon("u1", "p1", false);
            assertNotNull(c.await("A", 5000));
            assertEquals(202, AppFixture.post(node.adminPort(), "/admin/fixing/rate?event=0&instrument=0&rate=105", TOKEN));
            Map<String, String> fill = c.await("8", 5000);
            assertNotNull(fill, "the pending fixing execution reaches its owner after the restart");
            assertEquals("F", fill.get("150"));
            assertEquals("F1", fill.get("11"));
            assertEquals("105", fill.get("31"));
            c.close();
        }
    }

    @Test
    void promotionKeepsOrderControl() throws Exception {
        Properties p = fix(false);
        NodeConfig pCfg = AppFixture.config(dir, "pp", 0, -1, p);
        ClobNode primary = ClobNode.startPrimary(pCfg);
        NodeConfig sCfg = AppFixture.config(dir, "ss", -1, primary.replicationPort(), p);
        StandbyNode standby = new StandbyNode(sCfg);
        try {
            AppFixture.setUpCredit(primary.adminPort());
            TestFixClient c = connect(primary, "C1", "u1", "p1", true);
            c.order("LIVE", 1, 1, "100");
            String id = report(c, "LIVE").get("37");
            c.close();
            primary.pipeline().drain();
            long last = primary.pipeline().lastSequence();
            AppFixture.await(() -> standby.lastSequence() >= last, "standby caught up");
            primary.close();
            ClobNode promoted = standby.promote();
            TestFixClient d = connect(promoted, "C1", "u1", "p1", true);
            d.send("H", 11, "*");
            Map<String, String> status = d.await("8", 5000);
            assertEquals("LIVE", status.get("11"), "client can reconcile what is live");
            assertEquals("I", status.get("150"));
            d.order("AFTER", 1, 1, "99");
            String afterId = report(d, "AFTER").get("37");
            assertTrue(Long.parseLong(afterId) > Long.parseLong(id), "ids continue after promotion");
            d.send("F", 11, "CXL", 41, "LIVE", 55, "EUR/USD", 54, 1);
            assertEquals("4", report(d, "CXL").get("150"));
            d.close();
        } finally {
            standby.close();
        }
    }

    @Test
    void recoveryUsesASnapshotAndKeepsControlOfOrders() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "snap", -1, -1, fix(false));
        String id;
        long resume;
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            TestFixClient c = connect(node, "C1", "u1", "p1", true);
            c.order("LIVE", 1, 1, "100");
            id = report(c, "LIVE").get("37");
            assertEquals(202, AppFixture.post(node.adminPort(), "/admin/endofday", TOKEN));
            AppFixture.await(() -> Files.exists(cfg.dataDir().resolve("snapshots")) && !node.snapshotRunning()
                    && node.metrics().get("clob_snapshots_total") > 0, "end-of-day snapshot");
            c.order("AFTER", 1, 1, "99");
            report(c, "AFTER");
            resume = c.nextSeq;
            c.close();
            node.pipeline().drain();
        }
        // Destroying the journal's early part would lose it without a snapshot: prove the snapshot is what's used.
        SnapshotStore.Snapshot snapshot = SnapshotStore.latest(SnapshotJob.directory(cfg), ClobNode.recoverManifest(cfg).fingerprint());
        assertNotNull(snapshot);
        assertTrue(snapshot.sequence() > 0);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            TestFixClient c = new TestFixClient(node.fixPort(), "C1");
            c.nextSeq = resume;
            c.logon("u1", "p1", false);
            assertNotNull(c.await("A", 5000));
            c.send("F", 11, "C1X", 41, "LIVE", 55, "EUR/USD", 54, 1);
            Map<String, String> m = report(c, "C1X");
            assertEquals("4", m.get("150"));
            assertEquals(id, m.get("37"), "an order from before the snapshot");
            c.send("F", 11, "C2X", 41, "AFTER", 55, "EUR/USD", 54, 1);
            assertEquals("4", report(c, "C2X").get("150"), "an order from after it");
            c.close();
        }
    }

    @Test
    void aDamagedSnapshotFallsBackToTheJournal() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "dmg", -1, -1, fix(false));
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 1000, TimeInForce.GTC);
            node.pipeline().drain();
            assertEquals(202, AppFixture.post(node.adminPort(), "/admin/snapshot", TOKEN));
            AppFixture.await(() -> node.metrics().get("clob_snapshots_total") > 0, "snapshot");
        }
        Path file;
        try (var files = Files.list(SnapshotJob.directory(cfg))) {
            file = files.filter(f -> f.getFileName().toString().endsWith(".bin")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x55;
        Files.write(file, bytes);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.order(node.pipeline(), 2, 2, Side.SELL, 100, 1000, TimeInForce.IOC);
            AppFixture.await(() -> node.metrics().get("clob_trades_total") == 1, "trade against the order recovered from the journal");
        }
    }

    @Test
    void closeAppliesCancelOnDisconnectWhileSessionsAreActive() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "cod", -1, -1, fix(true));
        ClobNode node = ClobNode.startPrimary(cfg);
        AppFixture.setUpCredit(node.adminPort());
        TestFixClient c = connect(node, "C1", "u1", "p1", true);
        c.order("LIVE", 1, 1, "100");
        report(c, "LIVE");
        node.close();
        assertEquals(ClobNode.State.STOPPED, node.state());
        String log = AppFixture.read(cfg.dataDir().resolve("dropcopy.log"));
        assertTrue(log.contains("DISCONNECT"), log);
        Map<String, String> cancelled = c.await("8", 2000);
        assertNotNull(cancelled, "the client still receives the cancel before its socket closes");
        assertEquals("4", cancelled.get("150"));
        c.close();
    }

    @Test
    void closeDrainsQueuedWorkToOps() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "drain", -1, -1);
        ClobNode node = ClobNode.startPrimary(cfg);
        AppFixture.setUpCredit(node.adminPort());
        for (int i = 1; i <= 2000; i++) {
            AppFixture.order(node.pipeline(), i, 1 + i % 3, i % 2 == 0 ? Side.BUY : Side.SELL, 100, 1000, TimeInForce.GTC);
        }
        node.close();
        String log = AppFixture.read(cfg.dataDir().resolve("dropcopy.log"));
        assertTrue(log.contains("id=2000"), "the drop copy has the last order: " + log.length());
    }

    @Test
    void aFatalErrorDropsReadinessAndRefusesIngress() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "fatal", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            int admin = node.adminPort();
            assertEquals(200, status(admin, "/ready"));
            assertEquals(200, status(admin, "/health"));
            node.failForTest(new IllegalStateException("simulated agent failure"));
            assertEquals(503, status(admin, "/ready"));
            assertEquals(503, status(admin, "/health"));
            assertEquals(ClobNode.State.FAILED, node.state());
            UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
            assertFalse(node.pipeline().submit(b, 0, InputCodec.timeAdvance(b, 0)));
            assertEquals(503, AppFixture.post(admin, "/admin/endofday", TOKEN), "admin actions are refused too");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Shutdown"), expected.toString());
        }
    }

    @Test
    void anUnreadOutputRingCannotHangShutdown() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "stuck", -1, -1);
        ClobNode node = ClobNode.startPrimary(cfg);
        node.failForTest(new IllegalStateException("ops stopped"));
        long t0 = System.nanoTime();
        try {
            node.close();
        } catch (java.io.IOException ignored) {
            // a failed node reports its failure at close
        }
        assertTrue(System.nanoTime() - t0 < 30_000_000_000L, "bounded shutdown");
    }

    @Test
    void changedSeedOrRefDataFailsStartup() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "m", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            node.pipeline().drain();
        }
        Properties seed = new Properties();
        seed.setProperty("engine.randomSeed", "99");
        NodeConfig changedSeed = AppFixture.config(dir, "m", -1, -1, seed);
        assertThrows(JournalManifest.MismatchException.class, () -> ClobNode.startPrimary(changedSeed));
        Files.writeString(dir.resolve("refdata.txt"), Files.readString(dir.resolve("refdata.txt")).replace("minQty=1000", "minQty=2000"));
        assertThrows(JournalManifest.MismatchException.class, () -> ClobNode.startPrimary(cfg));
    }

    @Test
    void incompatibleStandbyIsRefused() throws Exception {
        NodeConfig pCfg = AppFixture.config(dir, "pm", 0, -1);
        try (ClobNode primary = ClobNode.startPrimary(pCfg)) {
            Properties other = new Properties();
            other.setProperty("engine.randomSeed", "2");
            NodeConfig sCfg = AppFixture.config(dir, "sm", -1, primary.replicationPort(), other);
            try (StandbyNode standby = new StandbyNode(sCfg)) {
                AppFixture.await(() -> standby.failure() != null, "refusal");
                assertTrue(standby.failure().contains("refused"), standby.failure());
                assertThrows(java.io.IOException.class, standby::promote);
            }
        }
    }

    @Test
    void aStandbyThatStopsReadingCannotStallThePrimary() throws Exception {
        Properties p = new Properties();
        p.setProperty("replication.deadlineMillis", "200");
        NodeConfig cfg = AppFixture.config(dir, "slow", 0, -1, p);
        try (ClobNode node = ClobNode.startPrimary(cfg); SocketChannel stuck = handshake(node, cfg, 0)) {
            AppFixture.setUpCredit(node.adminPort());
            for (int i = 1; i <= 100_000; i++) {
                AppFixture.order(node.pipeline(), i, 1, Side.BUY, 50, 1000, TimeInForce.IOC);
                if (i % 20_000 == 0) {
                    assertTrue(node.pipeline().drain(30_000_000_000L), "the primary keeps processing");
                }
            }
            AppFixture.await(() -> node.metrics().render().contains("clob_replication_connected 0"), "stalled standby dropped");
            assertNotNull(stuck);
        }
    }

    private static SocketChannel handshake(ClobNode node, NodeConfig cfg, long from) throws Exception {
        SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", node.replicationPort()));
        ch.socket().setReceiveBufferSize(4096);
        String fp = ClobNode.recoverManifest(cfg).fingerprint();
        ByteBuffer hello = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        hello.putLong(from).put(fp.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).flip();
        while (hello.hasRemaining()) {
            ch.write(hello);
        }
        ByteBuffer ack = ByteBuffer.allocate(1);
        ch.read(ack);
        assertEquals(1, ack.get(0));
        return ch;
    }

    @Test
    void aSilentConnectionDoesNotBlockARealStandby() throws Exception {
        NodeConfig pCfg = AppFixture.config(dir, "ps", 0, -1);
        try (ClobNode primary = ClobNode.startPrimary(pCfg);
                SocketChannel silent = SocketChannel.open(new InetSocketAddress("127.0.0.1", primary.replicationPort()))) {
            AppFixture.setUpCredit(primary.adminPort());
            primary.pipeline().drain();
            long last = primary.pipeline().lastSequence();
            NodeConfig sCfg = AppFixture.config(dir, "ssil", -1, primary.replicationPort());
            try (StandbyNode standby = new StandbyNode(sCfg)) {
                AppFixture.await(() -> standby.lastSequence() >= last, "the handshake timed out and the standby got in");
            }
            assertNotNull(silent);
        }
    }

    @Test
    void metricsAreValidPrometheus() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "prom", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 1000, TimeInForce.IOC);
            AppFixture.await(() -> AppFixture.get0(node.adminPort()).contains("clob_cancels_total{"), "cancel metric");
            String text = AppFixture.get(node.adminPort(), "/metrics");
            Pattern sample = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[a-zA-Z_][a-zA-Z0-9_]*=\"[^\"]*\"(,[a-zA-Z_][a-zA-Z0-9_]*=\"[^\"]*\")*\\})? -?\\d+");
            Pattern type = Pattern.compile("# TYPE [a-zA-Z_:][a-zA-Z0-9_:]* (counter|gauge)");
            Map<String, Integer> declared = new HashMap<>();
            for (String line : text.split("\n")) {
                if (line.startsWith("#")) {
                    assertTrue(type.matcher(line).matches(), line);
                    declared.merge(line.split(" ")[2], 1, Integer::sum);
                } else {
                    assertTrue(sample.matcher(line).matches(), line);
                }
            }
            declared.forEach((family, n) -> assertEquals(1, n, family + " is declared once"));
            assertTrue(text.contains("clob_engine_processed_sequence"));
            assertTrue(text.contains("clob_replication_lag"));
            assertTrue(text.contains("clob_md_max_tick_lateness_nanos"));
        }
    }

    @Test
    void udpMarketDataReachesTheSubscriber() throws Exception {
        Properties p = fix(false);
        p.setProperty("md.udp.enabled", "true");
        p.setProperty("md.udp.refreshCycles", "10");
        p.setProperty("node.idleStrategy", "backoff");
        NodeConfig cfg = AppFixture.config(dir, "udp", -1, -1, p);
        try (ClobNode node = ClobNode.startPrimary(cfg);
                java.nio.channels.DatagramChannel rx = java.nio.channels.DatagramChannel.open()
                        .bind(new InetSocketAddress("127.0.0.1", 0))) {
            rx.configureBlocking(false);
            AppFixture.setUpCredit(node.adminPort());
            TestFixClient c = connect(node, "C1", "u1", "p1", true);
            int port = ((InetSocketAddress) rx.getLocalAddress()).getPort();
            c.send("V", 262, "MD1", 263, 1, 264, 1, 55, "EUR/USD", 20016, port);
            c.order("LIVE", 1, 1, "100");
            report(c, "LIVE");
            ByteBuffer packet = ByteBuffer.allocate(65_000);
            clob.marketdata.BookView view = clob.marketdata.SnapshotCodec.newView();
            long deadline = System.currentTimeMillis() + 5000;
            int withBid = 0;
            while (System.currentTimeMillis() < deadline && withBid < 2) {
                packet.clear();
                if (rx.receive(packet) != null) {
                    clob.marketdata.SnapshotCodec.decode(new UnsafeBuffer(packet.array()), 0, view);
                    if (view.bidCount() == 1 && view.bidPrice(0) == 100) {
                        withBid++; // the second proves the periodic refresh of an unchanged book
                    }
                } else {
                    Thread.sleep(1);
                }
            }
            assertEquals(2, withBid, "snapshot delivered over UDP, then refreshed");
            c.close();
        }
    }

    @Test
    void udpSubscriptionIsRefusedWhenDisabled() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "noudp", -1, -1, fix(false));
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            TestFixClient c = connect(node, "C1", "u1", "p1", true);
            c.send("V", 262, "MD1", 263, 1, 55, "EUR/USD", 20016, 5000);
            assertEquals("UDP market data is not enabled", c.await("Y", 5000).get("58"));
            c.close();
        }
    }

    @Test
    void refDataCanRestrictTypesAndTifs() throws Exception {
        var r = RefDataLoader.parse(List.of("instrument 0 EUR/USD SPOT orderTypes=LIMIT tifs=DAY,IOC minQty=1000 qtyIncrement=1000"));
        assertEquals(java.util.Set.of(clob.refdata.OrderType.LIMIT), r.instrument(0).supportedOrderTypes());
        assertEquals(java.util.Set.of(clob.refdata.TimeInForce.DAY, clob.refdata.TimeInForce.IOC), r.instrument(0).supportedTifs());
        assertThrows(IllegalArgumentException.class, () -> RefDataLoader.parse(List.of("instrument 0 EUR/USD SPOT orderTypes=NOPE")));
        assertThrows(IllegalArgumentException.class, () -> RefDataLoader.parse(List.of("instrument 0 EUR/USD SPOT pegThrottleMs=0")));
        assertThrows(IllegalArgumentException.class, () -> RefDataLoader.parse(List.of("instrument 0 EUR/USD SPOT minTtlMicros=9223372036854775807")));
    }

    @Test
    void configurationIsValidatedAtStartup() throws Exception {
        Properties plain = new Properties();
        plain.setProperty("fix.session.1", "C1,u1,p1,1,true,0");
        assertThrows(IllegalArgumentException.class, () -> AppFixture.config(dir, "pl", -1, -1, plain), "plain text is refused by default");

        Path refData = dir.resolve("refdata.txt");
        Files.writeString(refData, "instrument 0 EUR/USD SPOT mdIntervalMs=3\nentitle 1 1\n");
        NodeConfig intervals = AppFixture.config(dir, "iv", -1, -1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ClobNode.startPrimary(intervals));
        assertTrue(e.getMessage().contains("multiple"), e.getMessage());

        Files.writeString(refData, "instrument 0 EUR/USD SPOT\nentitle 1 99\n");
        NodeConfig accounts = AppFixture.config(dir, "ac", -1, -1);
        e = assertThrows(IllegalArgumentException.class, () -> ClobNode.startPrimary(accounts));
        assertTrue(e.getMessage().contains("accounts.max"), e.getMessage());
    }

    @Test
    void invalidAdminInstrumentIsABadRequest() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "bad", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            assertEquals(400, AppFixture.post(node.adminPort(), "/admin/valuedates?instrument=999&near=2026-11-10", TOKEN));
            assertNull(node.fatalError());
        }
    }

    /** A real process: SIGTERM runs the staged shutdown; SIGKILL loses nothing the journal holds. */
    @Test
    void abruptProcessDeathRecovers() throws Exception {
        Path props = dir.resolve("node.properties");
        Files.writeString(dir.resolve("refdata.txt"), """
                instrument 0 EUR/USD SPOT precision=0 priceIncrement=1 minQty=1000 qtyIncrement=1000 maxQty=1000000 mdIntervalMs=5 minTtlMicros=0
                entitle 1 1
                entitle 2 2
                """);
        int fixPort = freePort();
        int adminPort = freePort();
        Files.writeString(props, String.join("\n", "refdata.file=refdata.txt", "journal.dir=x/journal", "data.dir=x/data", "accounts.max=8",
                "fix.port=" + fixPort, "admin.port=" + adminPort, "admin.token=" + TOKEN, "fix.allowPlaintextCredentials=true",
                "fix.session.1=C1,u1,p1,1,true,0", "fix.session.2=C2,u2,p2,2,false,0", "eod.schedule=", ""));
        Process p = start(props);
        waitReady(adminPort);
        credit(adminPort);
        TestFixClient c = new TestFixClient(fixPort, "C2");
        c.logon("u2", "p2", true);
        assertNotNull(c.await("A", 5000));
        c.order("LIVE", 2, 1, "100");
        report(c, "LIVE");
        p.destroyForcibly();
        p.waitFor();
        c.close();

        Properties extra = new Properties();
        extra.setProperty("fix.allowPlaintextCredentials", "true");
        extra.setProperty("fix.session.1", "C1,u1,p1,1,true,0");
        extra.setProperty("fix.session.2", "C2,u2,p2,2,false,0");
        extra.setProperty("eod.schedule", "");
        extra.setProperty("accounts.max", "8");
        p = start(props);
        waitReady(adminPort);
        TestFixClient d = new TestFixClient(fixPort, "C2");
        d.logon("u2", "p2", true);
        assertNotNull(d.await("A", 5000));
        d.send("H", 11, "LIVE");
        assertEquals("I", d.await("8", 5000).get("150"), "the order survived SIGKILL");
        // SIGTERM with a session open: the shutdown hook runs the staged close.
        TestFixClient cod = new TestFixClient(fixPort, "C1");
        cod.logon("u1", "p1", true);
        assertNotNull(cod.await("A", 5000));
        cod.order("COD", 1, 1, "90");
        report(cod, "COD");
        p.destroy();
        assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "SIGTERM completes in bounded time");
        String log = Files.readString(dir.resolve("x/data/dropcopy.log"));
        assertTrue(log.contains("DISCONNECT"), "cancel-on-disconnect ran at SIGTERM");
        d.close();
        cod.close();
    }

    private static void credit(int adminPort) throws Exception {
        for (int a = 1; a <= 2; a++) {
            AppFixture.post(adminPort, "/admin/credit/line?line=" + a + "&giver=" + a + "&type=UNLIMITED", TOKEN);
            AppFixture.post(adminPort, "/admin/credit/relationship?from=" + a + "&to=" + (3 - a) + "&line=" + a, TOKEN);
        }
        AppFixture.post(adminPort, "/admin/usdrate?ccy=EUR&rate=1.1", TOKEN);
        AppFixture.post(adminPort, "/admin/valuedates?instrument=0&near=2026-11-10", TOKEN);
    }

    private static int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Process start(Path props) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                "clob.app.Main", "primary", props.toString()).redirectErrorStream(true)
                .redirectOutput(props.resolveSibling("node.log").toFile()).start();
    }

    private static void waitReady(int adminPort) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (status(adminPort, "/ready") == 200) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                // still starting
            }
            Thread.sleep(20);
        }
        throw new AssertionError("node did not become ready");
    }
}
