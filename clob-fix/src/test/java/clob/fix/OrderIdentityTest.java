package clob.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R01, R02, R03, R04 and R17 over a real FIX socket. */
class OrderIdentityTest {
    static final List<FixSessionConfig> KEEP_ORDERS = List.of(
            new FixSessionConfig("C1", "u1", "p1", 1, false, 0),
            new FixSessionConfig("C2", "u2", "p2", 2, false, 0));

    @TempDir
    Path dir;

    private static void newOrder(RawFixClient c, String clOrdId, int account, int side, String price, String tenor) throws Exception {
        c.send("D", 11, clOrdId, 1, account, 55, "EUR/USD", 20013, tenor, 54, side, 38, 1000, 40, 2, 59, 1, 44, price);
    }

    private static RawFixClient loggedOn(GatewayHarness h, String sender, String user, String password) throws Exception {
        RawFixClient c = new RawFixClient(h.port(), sender);
        c.logon(user, password);
        assertNotNull(c.await("A", 3000));
        return c;
    }

    private static Map<Integer, String> ack(RawFixClient c, String clOrdId) throws Exception {
        Map<Integer, String> m = c.await("8", 3000);
        assertNotNull(m);
        assertEquals(clOrdId, m.get(11));
        return m;
    }

    @Test
    void reconnectKeepsControlOfLiveOrders() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "LIVE", 1, 1, "1.00000", "SPOT");
            ack(c, "LIVE");
            c.close();
            Thread.sleep(50);
            RawFixClient again = loggedOn(h, "C1", "u1", "p1");
            again.send("F", 11, "CXL", 41, "LIVE", 55, "EUR/USD", 54, 1);
            Map<Integer, String> m = ack(again, "CXL");
            assertEquals("4", m.get(150));
            assertEquals("LIVE", m.get(41));
            again.close();
        }
    }

    @Test
    void reportsMissedWhileDisconnectedAreResent() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "A", 1, 1, "1.00000", "SPOT");
            ack(c, "A");
            long next = c.nextSeq;
            c.close();
            Thread.sleep(50);
            RawFixClient other = loggedOn(h, "C2", "u2", "p2");
            newOrder(other, "B", 2, 2, "1.00000", "SPOT");
            ack(other, "B");
            Thread.sleep(100); // C1's fill is reported while it is away

            RawFixClient back = new RawFixClient(h.port(), "C1");
            back.nextSeq = next;
            back.logonResume("u1", "p1");
            Map<Integer, String> logon = back.await("A", 3000);
            assertNotNull(logon);
            back.send("2", 7, 1, 16, 0);
            boolean sawFill = false;
            for (int i = 0; i < 10 && !sawFill; i++) {
                Map<Integer, String> m = back.await("8", 1000);
                if (m == null) {
                    break;
                }
                sawFill = "F".equals(m.get(150)) && "A".equals(m.get(11)) && "Y".equals(m.get(43));
            }
            assertTrue(sawFill, "the fill that happened while disconnected was resent");
            back.close();
            other.close();
        }
    }

    @Test
    void aRequestCannotReuseAnotherOrdersClOrdId() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "A", 1, 1, "1.00000", "SPOT");
            ack(c, "A");
            newOrder(c, "B", 1, 1, "0.99000", "SPOT");
            ack(c, "B");
            c.send("F", 11, "B", 41, "A", 55, "EUR/USD", 54, 1);
            Map<Integer, String> reject = c.await("9", 3000);
            assertEquals("Duplicate ClOrdID", reject.get(58));
            c.send("F", 11, "CXL", 41, "B", 55, "EUR/USD", 54, 1);
            Map<Integer, String> m = ack(c, "CXL");
            assertEquals("4", m.get(150), "B is still cancellable");
            assertEquals("B", m.get(41));
            c.close();
        }
    }

    @Test
    void overlappingRequestsAreEachAnswered() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "A", 1, 1, "1.00000", "TTL");
            ack(c, "A");
            // All inside the TTL: replace, replace again, then a cancel that supersedes the second.
            c.send("G", 11, "R1", 41, "A", 55, "EUR/USD", 20013, "TTL", 54, 1, 38, 2000, 44, "1.00000", 40, 2);
            Map<Integer, String> queued = c.await("8", 3000);
            assertEquals("E", queued.get(150));
            assertEquals("R1", queued.get(11));
            c.send("G", 11, "R2", 41, "A", 55, "EUR/USD", 20013, "TTL", 54, 1, 38, 3000, 44, "1.00000", 40, 2);
            Map<Integer, String> superseded = c.await("9", 3000);
            assertEquals("R1", superseded.get(11), "the reject names the request it answers");
            assertEquals("SUPERSEDED", superseded.get(58));
            assertEquals("R2", c.await("8", 3000).get(11));
            c.send("F", 11, "C1", 41, "A", 55, "EUR/USD", 20013, "TTL", 54, 1);
            assertEquals("R2", c.await("9", 3000).get(11));
            assertEquals("C1", c.await("8", 3000).get(11));
            c.send("F", 11, "C2", 41, "A", 55, "EUR/USD", 20013, "TTL", 54, 1);
            Map<Integer, String> rejected = c.await("9", 3000);
            assertEquals("C2", rejected.get(11));
            assertEquals("CANCEL_ALREADY_PENDING", rejected.get(58));
            Map<Integer, String> done = c.await("8", 3000);
            assertEquals("4", done.get(150));
            assertEquals("C1", done.get(11), "the queued cancel completes under its own ClOrdID");
            c.close();
        }
    }

    @Test
    void replaceInstallsItsOwnClOrdId() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "A", 1, 1, "1.00000", "SPOT");
            ack(c, "A");
            c.send("G", 11, "R", 41, "A", 55, "EUR/USD", 54, 1, 38, 2000, 44, "0.99000", 40, 2);
            Map<Integer, String> m = ack(c, "R");
            assertEquals("5", m.get(150));
            c.send("F", 11, "CXL", 41, "R", 55, "EUR/USD", 54, 1);
            assertEquals("4", ack(c, "CXL").get(150));
            c.close();
        }
    }

    @Test
    void orderStatusReportsLiveOrders() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            newOrder(c, "A", 1, 1, "1.00000", "SPOT");
            ack(c, "A");
            c.send("H", 11, "*");
            Map<Integer, String> m = c.await("8", 3000);
            assertEquals("I", m.get(150));
            assertEquals("A", m.get(11));
            c.send("H", 11, "UNKNOWN");
            assertEquals("Unknown order", c.await("8", 3000).get(58));
            c.close();
        }
    }

    @Test
    void malformedMessagesOnlyAffectTheirConnection() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient bad = loggedOn(h, "C1", "u1", "p1");
            RawFixClient good = loggedOn(h, "C2", "u2", "p2");
            bad.send("D", 11, "X", 1, "99999999999999999999", 55, "EUR/USD", 54, 1, 38, 1000, 40, 2, 44, "1");
            Map<Integer, String> reject = bad.await("8", 3000);
            assertEquals("8", reject.get(150), "an out-of-range account is a per-order reject");
            bad.send("1", 112, "probe"); // still alive
            assertNotNull(bad.await("0", 3000));
            bad.send("2", 7, "-", 16, 0); // lone minus
            assertNotNull(bad.await("5", 3000), "a malformed session message logs out only this session");

            newOrder(good, "G1", 2, 1, "1.00000", "SPOT");
            ack(good, "G1");
            good.close();
        }
    }

    @Test
    void mismatchedCompIdsAfterLogonEndTheSession() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = loggedOn(h, "C1", "u1", "p1");
            RawFixClient spoof = new RawFixClient(h.port(), "C2");
            spoof.close();
            c.sendRaw(fix("D", "C2", c.nextSeq++));
            assertEquals("SenderCompID or TargetCompID does not match the session", c.await("5", 3000).get(58));
            c.close();
        }
    }

    private static byte[] fix(String type, String sender, long seq) {
        String body = "35=" + type + "\u000149=" + sender + "\u000156=CLOB\u000134=" + seq + "\u000152=20261108-00:00:00.000\u0001";
        String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001" + body;
        int sum = 0;
        for (byte b : head.getBytes(StandardCharsets.US_ASCII)) {
            sum += b;
        }
        return (head + String.format("10=%03d\u0001", sum & 0xFF)).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void oversizedBodyLengthDropsTheConnection() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, KEEP_ORDERS)) {
            RawFixClient c = new RawFixClient(h.port(), "C1");
            c.sendRaw("8=FIX.4.4\u00019=9999999\u000135=A\u0001".getBytes(StandardCharsets.US_ASCII));
            assertNull(c.read(500), "no reply: it is not a FIX message we accept");
            c.close();
        }
    }

    @Test
    void parserRejectsOverflowAndDigitlessNumbers() {
        FixParser p = new FixParser();
        byte[] msg = fixFields("35=0\u000134=1\u0001999=99999999999999999999\u0001998=-\u0001997=.\u0001996=1.5\u0001");
        org.junit.jupiter.api.Assertions.assertNull(p.parse(msg, 0, msg.length));
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class, () -> p.asLong(999));
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class, () -> p.asLong(998));
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class, () -> p.decimal(997, 2));
        assertEquals(150, p.decimal(996, 2));
    }

    private static byte[] fixFields(String body) {
        String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001" + body;
        int sum = 0;
        for (byte b : head.getBytes(StandardCharsets.US_ASCII)) {
            sum += b;
        }
        return (head + String.format("10=%03d\u0001", sum & 0xFF)).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void hashedPasswordsAuthenticate() {
        String stored = FixSessionConfig.hash("secret");
        FixSessionConfig cfg = new FixSessionConfig("C", "u", stored, 1, false, 0);
        assertTrue(cfg.hasHashedPassword());
        assertTrue(cfg.passwordMatches("secret"));
        org.junit.jupiter.api.Assertions.assertFalse(cfg.passwordMatches("wrong"));
        org.junit.jupiter.api.Assertions.assertFalse(cfg.passwordMatches(null));
    }

    /** R04: sustained two-way traffic through deliberately tiny rings must make progress and finish. */
    @Test
    void tinyRingsDoNotDeadlock() throws Exception {
        try (GatewayHarness h = new GatewayHarness(dir, List.of(new FixSessionConfig("C1", "u1", "p1", 1, false, 0),
                new FixSessionConfig("C2", "u2", "p2", 2, false, 0)), 1 << 11, 1 << 12)) {
            RawFixClient a = loggedOn(h, "C1", "u1", "p1");
            RawFixClient b = loggedOn(h, "C2", "u2", "p2");
            int orders = 3000;
            List<Thread> readers = new ArrayList<>();
            int[] seen = new int[2];
            for (int k = 0; k < 2; k++) {
                int which = k;
                RawFixClient c = k == 0 ? a : b;
                Thread t = new Thread(() -> {
                    try {
                        while (seen[which] < orders) {
                            if (c.await("8", 20_000) == null) {
                                return;
                            }
                            seen[which]++;
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                t.start();
                readers.add(t);
            }
            for (int i = 0; i < orders; i++) {
                newOrder(a, "A" + i, 1, 1, "1.00000", "SPOT");
                newOrder(b, "B" + i, 2, 2, "1.00000", "SPOT");
            }
            for (Thread t : readers) {
                t.join(60_000);
            }
            assertTrue(seen[0] >= orders && seen[1] >= orders, "progress: " + seen[0] + ", " + seen[1]);
            a.close();
            b.close();
        }
    }
}
