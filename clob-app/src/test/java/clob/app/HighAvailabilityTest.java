package clob.app;

import static clob.app.AppFixture.TOKEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import java.nio.file.Path;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Warm standby: replicate, fail the primary, promote, carry on with the same state. */
class HighAvailabilityTest {
    @TempDir
    Path dir;

    private static long lastSeq(List<byte[]> journal) {
        return InputCodec.sequence(new UnsafeBuffer(journal.get(journal.size() - 1)), 0);
    }

    private void failoverScenario(boolean lateJoin) throws Exception {
        NodeConfig pCfg = AppFixture.config(dir, "primary", 0, -1);
        ClobNode primary = ClobNode.startPrimary(pCfg);
        NodeConfig sCfg = AppFixture.config(dir, "standby", -1, primary.replicationPort());
        StandbyNode standby = lateJoin ? null : new StandbyNode(sCfg);
        try {
            AppFixture.setUpCredit(primary.adminPort());
            for (int i = 1; i <= 50; i++) {
                AppFixture.order(primary.pipeline(), i, 1 + i % 3, i % 2 == 0 ? Side.BUY : Side.SELL, 90 + i % 20, 1000,
                        TimeInForce.GTC);
            }
            AppFixture.order(primary.pipeline(), 100, 1, Side.BUY, 50, 5000, TimeInForce.GTC); // rests far from the market
            primary.pipeline().drain();
            if (lateJoin) {
                standby = new StandbyNode(sCfg); // catches up from the primary's journal
            }
            StandbyNode s = standby;
            AppFixture.await(() -> s.lastSequence() >= 60, "standby following");
            primary.close(); // the primary fails
            List<byte[]> primaryJournal = AppFixture.journal(pCfg.journalDir());
            long last = lastSeq(primaryJournal);
            AppFixture.await(() -> s.lastSequence() == last, "standby caught up to " + last);
            List<byte[]> standbyJournal = AppFixture.journal(sCfg.journalDir());
            assertEquals(primaryJournal.size(), standbyJournal.size());
            for (int i = 0; i < primaryJournal.size(); i++) {
                assertArrayEquals(primaryJournal.get(i), standbyJournal.get(i), "record " + i);
            }

            assertTrue(AppFixture.get(s.adminPort(), "/health").startsWith("STANDBY"));
            assertEquals(202, AppFixture.post(s.adminPort(), "/admin/promote", TOKEN));
            AppFixture.await(() -> s.promoted() != null, "promotion");
            ClobNode promoted = s.promoted();
            // Bids that rested before the failover are still there on the promoted node.
            AppFixture.order(promoted.pipeline(), 200, 2, Side.SELL, 50, 1000, TimeInForce.IOC);
            AppFixture.await(() -> promoted.metrics().get("clob_trades_total") >= 1, "trade on the promoted node");
            java.util.regex.Pattern preFailoverFill = java.util.regex.Pattern.compile("aggressor=200 passive=(\\d+) ");
            AppFixture.await(() -> {
                java.util.regex.Matcher m = preFailoverFill.matcher(AppFixture.read(sCfg.dataDir().resolve("dropcopy.log")));
                return m.find() && Long.parseLong(m.group(1)) <= 100;
            }, "a fill against an order that rested before the failover");
            promoted.pipeline().drain();
            assertTrue(lastSeq(AppFixture.journal(sCfg.journalDir())) > last, "the sequence continues");
        } finally {
            if (standby != null) {
                standby.close();
            }
        }
    }

    @Test
    void standbyReplicatesAndTakesOver() throws Exception {
        failoverScenario(false);
    }

    @Test
    void lateJoiningStandbyCatchesUpFromTheJournal() throws Exception {
        failoverScenario(true);
    }
}
