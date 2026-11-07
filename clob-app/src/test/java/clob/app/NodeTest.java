package clob.app;

import static clob.app.AppFixture.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeTest {
    @TempDir
    Path dir;

    @Test
    void exampleRefDataParses() throws Exception {
        RefData r = RefDataLoader.load(Path.of("../config/refdata.txt"));
        assertEquals("USD/MXN", r.instrument(1).symbol());
        assertEquals(5, r.instrument(1).priceIncrement());
        assertTrue(r.isEntitled(2, 2));
        assertEquals("WMR16", r.fixingEvent(0).name());
    }

    @Test
    void refDataErrorsNameTheLine() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RefDataLoader.parse(List.of("# comment", "instrument 0 EUR/USD SPOT bogus=1")));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void primaryTradesAndReportsThroughOpsAndAdmin() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "p", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            int admin = node.adminPort();
            assertEquals(401, AppFixture.post(admin, "/admin/endofday", null));
            assertEquals(400, AppFixture.post(admin, "/admin/session?state=NOPE", TOKEN));
            AppFixture.setUpCredit(admin);
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 2000, TimeInForce.GTC);
            AppFixture.order(node.pipeline(), 2, 2, Side.SELL, 100, 1000, TimeInForce.IOC);
            AppFixture.await(() -> node.metrics().get("clob_trades_total") == 1, "trade");
            String metrics = AppFixture.get(admin, "/metrics");
            assertTrue(metrics.contains("clob_trades_total 1"), metrics);
            assertTrue(metrics.contains("clob_traded_qty_total 1000"), metrics);
            assertTrue(AppFixture.get(admin, "/health").startsWith("PRIMARY"));
            AppFixture.await(() -> AppFixture.read(cfg.dataDir().resolve("trades.csv")).lines().count() == 2, "trade report");
            assertTrue(AppFixture.read(cfg.dataDir().resolve("dropcopy.log")).contains("TRADE trade=1"));
        }
    }

    @Test
    void killSwitchHaltsAndCancelsEverything() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "k", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 1000, TimeInForce.GTC);
            AppFixture.order(node.pipeline(), 2, 2, Side.SELL, 110, 1000, TimeInForce.GTC);
            assertEquals(202, AppFixture.post(node.adminPort(), "/admin/killswitch", TOKEN));
            AppFixture.order(node.pipeline(), 3, 3, Side.BUY, 105, 1000, TimeInForce.GTC);
            AppFixture.await(() -> AppFixture.read(cfg.dataDir().resolve("dropcopy.log")).contains("REJECTED id=3"), "reject");
            String log = AppFixture.read(cfg.dataDir().resolve("dropcopy.log"));
            assertTrue(log.contains("SESSION_STATE instr=0 HALTED"), log);
            assertTrue(log.contains("CANCELLED id=1 ADMIN") && log.contains("CANCELLED id=2 ADMIN"), log);
            assertTrue(log.contains("REJECTED id=3 MARKET_HALTED"), log);
        }
    }

    @Test
    void restartRecoversStateWithoutRepeatingOutput() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "r", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 1000, TimeInForce.GTC);
            AppFixture.await(() -> AppFixture.read(cfg.dataDir().resolve("dropcopy.log")).contains("ACCEPTED id=1"), "ack");
        }
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.order(node.pipeline(), 2, 2, Side.SELL, 100, 1000, TimeInForce.IOC);
            AppFixture.await(() -> node.metrics().get("clob_trades_total") == 1, "trade against the recovered order");
            String log = AppFixture.read(cfg.dataDir().resolve("dropcopy.log"));
            assertEquals(1, log.lines().filter(l -> l.contains("ACCEPTED id=1")).count(), "replay did not re-emit output");
        }
    }

    @Test
    void auditToolPrintsInputsAndReplayedOutput() throws Exception {
        NodeConfig cfg = AppFixture.config(dir, "a", -1, -1);
        try (ClobNode node = ClobNode.startPrimary(cfg)) {
            AppFixture.setUpCredit(node.adminPort());
            AppFixture.order(node.pipeline(), 1, 1, Side.BUY, 100, 1000, TimeInForce.GTC);
            AppFixture.order(node.pipeline(), 2, 2, Side.SELL, 100, 1000, TimeInForce.IOC);
            node.pipeline().drain();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AuditTool.print(cfg.journalDir(), cfg.refDataFile(), cfg.maxAccounts(), cfg.randomSeed(), new PrintStream(out));
        String text = out.toString();
        assertTrue(text.contains("NEW_ORDER id=2 instr=0 acct=2"), text);
        assertTrue(text.contains("-> time=") && text.contains("TRADE trade=1"), text);
        assertFalse(text.contains("refused"), text);
    }
}
