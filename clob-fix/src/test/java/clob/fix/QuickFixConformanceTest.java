package clob.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Password;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.field.Username;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

/**
 * Conformance against an independent FIX engine: QuickFIX/J initiators with full FIX 4.4 dictionary
 * validation of everything the gateway sends (custom tags allowed).
 */
class QuickFixConformanceTest {
    @TempDir
    Path dir;
    GatewayHarness h;
    SocketInitiator initiator;
    final Client c1 = new Client("QF1", "user1", "pw1");
    final Client c2 = new Client("QF2", "user2", "pw2");

    static final class Client implements Application {
        final String compId;
        final String user;
        final String password;
        final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        SessionID session;

        Client(String compId, String user, String password) {
            this.compId = compId;
            this.user = user;
            this.password = password;
        }

        @Override public void onCreate(SessionID s) { }
        @Override public void onLogon(SessionID s) { }
        @Override public void onLogout(SessionID s) { }
        @Override public void toApp(Message m, SessionID s) { }
        @Override public void fromAdmin(Message m, SessionID s) { }

        @Override
        public void toAdmin(Message m, SessionID s) {
            try {
                if (MsgType.LOGON.equals(m.getHeader().getString(MsgType.FIELD))) {
                    m.setField(new Username(user));
                    m.setField(new Password(password));
                }
            } catch (FieldNotFound e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void fromApp(Message m, SessionID s) {
            received.add(m);
        }

        Message next(String msgType) throws Exception {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Message m = received.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && msgType.equals(m.getHeader().getString(MsgType.FIELD))) {
                    return m;
                }
            }
            throw new AssertionError("no " + msgType + " for " + compId);
        }

        void send(Message m) throws quickfix.SessionNotFound {
            assertTrue(Session.sendToTarget(m, session));
        }
    }

    @BeforeEach
    void start() throws Exception {
        h = new GatewayHarness(dir, List.of(
                new FixSessionConfig("QF1", "user1", "pw1", 1, true, 0),
                new FixSessionConfig("QF2", "user2", "pw2", 2, true, 0),
                new FixSessionConfig("QF3", "user3", "pw3", 3, true, 0)));
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setString("HeartBtInt", "30");
        settings.setString("ReconnectInterval", "1");
        settings.setString("ResetOnLogon", "Y");
        settings.setString("UseDataDictionary", "Y");
        settings.setString("ValidateUserDefinedFields", "N");
        settings.setString("AllowUnknownMsgFields", "Y");
        settings.setString("SocketConnectHost", "127.0.0.1");
        settings.setLong("SocketConnectPort", h.port());
        java.util.Map<SessionID, Client> apps = new java.util.HashMap<>();
        for (Client c : List.of(c1, c2)) {
            c.session = new SessionID("FIX.4.4", c.compId, GatewayHarness.COMP_ID);
            settings.setString(c.session, "BeginString", "FIX.4.4");
            apps.put(c.session, c);
        }
        Application router = new Application() {
            @Override public void onCreate(SessionID s) { apps.get(s).onCreate(s); }
            @Override public void onLogon(SessionID s) { }
            @Override public void onLogout(SessionID s) { }
            @Override public void toAdmin(Message m, SessionID s) { apps.get(s).toAdmin(m, s); }
            @Override public void fromAdmin(Message m, SessionID s) { }
            @Override public void toApp(Message m, SessionID s) { }
            @Override public void fromApp(Message m, SessionID s) { apps.get(s).fromApp(m, s); }
        };
        initiator = new SocketInitiator(router, new MemoryStoreFactory(), settings, new ScreenLogFactory(false, false, false),
                new DefaultMessageFactory());
        initiator.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!(loggedOn(c1) && loggedOn(c2)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(loggedOn(c1) && loggedOn(c2), "both sessions logged on");
    }

    private static boolean loggedOn(Client c) {
        Session s = Session.lookupSession(c.session);
        return s != null && s.isLoggedOn();
    }

    @AfterEach
    void stop() throws Exception {
        initiator.stop(true);
        h.close();
    }

    static NewOrderSingle limit(String clOrdId, int account, char side, double price, double qty) {
        NewOrderSingle o = new NewOrderSingle(new ClOrdID(clOrdId), new Side(side), new TransactTime(LocalDateTime.now()),
                new OrdType(OrdType.LIMIT));
        o.set(new Account(String.valueOf(account)));
        o.set(new Symbol("EUR/USD"));
        o.set(new OrderQty(qty));
        o.set(new Price(price));
        o.set(new TimeInForce(TimeInForce.DAY));
        return o;
    }

    @Test
    void newOrderIsAcknowledged() throws Exception {
        c1.send(limit("A1", 1, Side.BUY, 1.08123, 1000));
        Message ack = c1.next("8");
        assertEquals('0', ack.getChar(150));
        assertEquals('0', ack.getChar(39));
        assertEquals("A1", ack.getString(11));
        assertEquals(1000, ack.getDouble(151), 0);
    }

    @Test
    void crossingOrdersFillBothSides() throws Exception {
        c1.send(limit("B1", 1, Side.BUY, 1.08125, 2000));
        c1.next("8");
        c2.send(limit("S1", 2, Side.SELL, 1.08120, 1000));
        c2.next("8"); // new
        Message sellFill = c2.next("8");
        Message buyFill = c1.next("8");
        assertEquals('F', sellFill.getChar(150));
        assertEquals('2', sellFill.getChar(39));
        assertEquals(1.08125, sellFill.getDouble(31), 1e-9, "trades at the resting price");
        assertEquals(1000, sellFill.getDouble(32), 0);
        assertEquals('F', buyFill.getChar(150));
        assertEquals('1', buyFill.getChar(39));
        assertEquals(1000, buyFill.getDouble(151), 0);
        assertEquals(1000, buyFill.getDouble(14), 0);
        assertEquals(1.08125, buyFill.getDouble(6), 1e-9);
    }

    @Test
    void replaceThenCancel() throws Exception {
        c1.send(limit("R1", 1, Side.BUY, 1.08000, 1000));
        c1.next("8");
        OrderCancelReplaceRequest g = new OrderCancelReplaceRequest(new OrigClOrdID("R1"), new ClOrdID("R2"),
                new Side(Side.BUY), new TransactTime(LocalDateTime.now()), new OrdType(OrdType.LIMIT));
        g.set(new Symbol("EUR/USD"));
        g.set(new OrderQty(3000));
        g.set(new Price(1.08010));
        c1.send(g);
        Message replaced = c1.next("8");
        assertEquals('5', replaced.getChar(150));
        assertEquals("R2", replaced.getString(11));
        assertEquals("R1", replaced.getString(41));
        assertEquals(3000, replaced.getDouble(38), 0);
        OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("R2"), new ClOrdID("R3"), new Side(Side.BUY),
                new TransactTime(LocalDateTime.now()));
        f.set(new Symbol("EUR/USD"));
        c1.send(f);
        Message cancelled = c1.next("8");
        assertEquals('4', cancelled.getChar(150));
        assertEquals("R3", cancelled.getString(11));
        assertEquals("R2", cancelled.getString(41));
    }

    @Test
    void cancelOfUnknownOrderIsRejected() throws Exception {
        OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("NOPE"), new ClOrdID("C9"), new Side(Side.BUY),
                new TransactTime(LocalDateTime.now()));
        f.set(new Symbol("EUR/USD"));
        c1.send(f);
        Message reject = c1.next("9");
        assertEquals(1, reject.getInt(102));
    }

    @Test
    void invalidPricePrecisionAndDuplicateClOrdIdAreRejected() throws Exception {
        c1.send(limit("P1", 1, Side.BUY, 1.081234, 1000));
        Message r1 = c1.next("8");
        assertEquals('8', r1.getChar(150));
        c1.send(limit("P2", 1, Side.BUY, 1.08000, 1000));
        c1.next("8");
        c1.send(limit("P2", 1, Side.BUY, 1.08000, 1000));
        Message r2 = c1.next("8");
        assertEquals('8', r2.getChar(150));
        assertEquals(6, r2.getInt(103));
    }

    @Test
    void engineRejectionsAreReported() throws Exception {
        c1.send(limit("E1", 2, Side.BUY, 1.08000, 1000)); // user 1 is not entitled to account 2
        Message r = c1.next("8");
        assertEquals('8', r.getChar(150));
        assertEquals("NOT_ENTITLED", r.getString(58));
    }

    @Test
    void ordersAreCancelledWhenTheSessionDisconnects() throws Exception {
        c1.send(limit("D1", 1, Side.BUY, 1.08000, 1000));
        c1.next("8");
        Session.lookupSession(c1.session).logout();
        long deadline = System.currentTimeMillis() + 5000;
        while (loggedOn(c1) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(loggedOn(c1));
        Thread.sleep(200);
        NewOrderSingle sell = limit("D2", 2, Side.SELL, 1.08000, 1000);
        sell.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        c2.send(sell);
        c2.next("8"); // new
        Message after = c2.next("8");
        assertEquals('4', after.getChar(150), "no liquidity left: the IOC is cancelled, not filled");
        assertNotNull(after.getString(58));
    }
}
