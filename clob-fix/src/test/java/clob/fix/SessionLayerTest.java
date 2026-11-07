package clob.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLayerTest {
    @TempDir
    Path dir;
    GatewayHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new GatewayHarness(dir, List.of(
                new FixSessionConfig("RAW1", "user1", "secret1", 1, true, 0),
                new FixSessionConfig("SLOW", "user2", "secret2", 2, false, 5)));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private static Object[] order(String clOrdId, char side, String price) {
        return new Object[] {11, clOrdId, 1, 1, 55, "EUR/USD", 54, side, 38, 1000, 40, '2', 44, price, 59, '0',
                60, "20261107-12:00:00.000"};
    }

    @Test
    void logonIsAnsweredAndBadCredentialsAreDisconnected() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "RAW1")) {
            c.logon("user1", "secret1");
            Map<Integer, String> logon = c.await("A", 3000);
            assertNotNull(logon);
            assertEquals("1", logon.get(34));
        }
        try (RawFixClient bad = new RawFixClient(h.port(), "RAW1")) {
            bad.logon("user1", "wrong");
            assertNull(bad.await("A", 1000));
        }
    }

    @Test
    void testRequestIsAnsweredWithHeartbeat() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "RAW1")) {
            c.logon("user1", "secret1");
            c.await("A", 3000);
            c.send("1", 112, "PING-1");
            assertEquals("PING-1", c.await("0", 3000).get(112));
        }
    }

    @Test
    void resendRequestGapFillsAdminAndResendsApplicationMessages() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "RAW1")) {
            c.logon("user1", "secret1");
            c.await("A", 3000);
            c.send("D", order("O1", '1', "1.08123"));
            Map<Integer, String> ack = c.await("8", 3000);
            assertEquals("0", ack.get(150));
            assertEquals("2", ack.get(34));
            c.send("2", 7, 1, 16, 0);
            Map<Integer, String> gapFill = c.await("4", 3000);
            assertEquals("Y", gapFill.get(123));
            assertEquals("1", gapFill.get(34));
            assertEquals("2", gapFill.get(36));
            Map<Integer, String> resent = c.await("8", 3000);
            assertEquals("Y", resent.get(43));
            assertEquals("2", resent.get(34));
            assertEquals("O1", resent.get(11));
        }
    }

    @Test
    void inboundGapTriggersResendRequestAndLowSeqLogsOut() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "RAW1")) {
            c.logon("user1", "secret1");
            c.await("A", 3000);
            c.sendWithSeq(5, "0");
            Map<Integer, String> rr = c.await("2", 3000);
            assertEquals("2", rr.get(7));
            c.sendWithSeq(1, "0"); // too low, no PossDup
            assertNotNull(c.await("5", 3000));
        }
    }

    @Test
    void throttledSessionGetsBusinessRejects() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "SLOW")) {
            c.logon("user2", "secret2");
            c.await("A", 3000);
            for (int i = 0; i < 20; i++) {
                c.send("D", 11, "S" + i, 1, 2, 55, "EUR/USD", 54, '1', 38, 1000, 40, '2', 44, "1.00000", 59, '0');
            }
            int rejects = 0;
            Map<Integer, String> m;
            while ((m = c.read(1500)) != null) {
                if ("j".equals(m.get(35))) {
                    rejects++;
                    assertEquals("Throttled", m.get(58));
                }
            }
            assertTrue(rejects >= 10, "rejects " + rejects);
        }
    }

    @Test
    void missingRequiredTagIsASessionReject() throws Exception {
        try (RawFixClient c = new RawFixClient(h.port(), "RAW1")) {
            c.logon("user1", "secret1");
            c.await("A", 3000);
            c.send("D", 11, "X", 54, '1', 38, 1000, 55, "EUR/USD"); // no Account
            Map<Integer, String> r = c.await("3", 3000);
            assertEquals("D", r.get(372));
        }
    }
}
