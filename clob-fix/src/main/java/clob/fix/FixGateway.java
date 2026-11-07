package clob.fix;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.CancelSource;
import clob.engine.MassCancelScope;
import clob.engine.NewOrderCommand;
import clob.engine.RejectReason;
import clob.engine.ReplaceCommand;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import clob.engine.SessionState;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputReader;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * FIX 4.4 order-entry acceptor. Runs on its own thread (an Agrona {@link Agent}): accepts TCP
 * connections, runs the FIX session layer, turns application messages into engine inputs for the
 * {@link Pipeline}, and turns engine output (from an {@link OutputRing}) into execution reports.
 * See {@code docs/FIX-RoE.md} for the rules of engagement.
 *
 * <p>Session layer: Logon with Username/Password (553/554) and ResetSeqNumFlag (141), Heartbeat,
 * TestRequest, ResendRequest (application messages resent with PossDupFlag; admin messages replaced
 * by SequenceReset-GapFill), SequenceReset (gap fill and reset), Logout, and inbound gap detection.
 * Sequence numbers and the resend store live in memory: a gateway restart requires a sequence reset
 * (PLAN Q41).
 */
public final class FixGateway implements Agent, AutoCloseable {
    private final FixGatewayConfig config;
    private final RefData refData;
    private final Pipeline pipeline;
    private final OutputRing outputs;
    private final LongSupplier wallMillis;
    private final Map<String, FixSessionConfig> sessionConfigs = new HashMap<>();
    private final Selector selector;
    private final ServerSocketChannel server;
    private final List<FixSession> sessions = new ArrayList<>();
    private final Long2ObjectHashMap<GatewayOrder> orders = new Long2ObjectHashMap<>();
    private final FixParser parser = new FixParser();
    private final FixEncoder enc = new FixEncoder();
    private final OutputReader event = new OutputReader();
    private final UnsafeBuffer cmd = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private long nextOrderId;
    private long nextExecId;
    private long nextMassCancelId;

    public FixGateway(FixGatewayConfig config, RefData refData, Pipeline pipeline, OutputRing outputs,
            LongSupplier wallMillis) throws IOException {
        this.config = config;
        this.refData = refData;
        this.pipeline = pipeline;
        this.outputs = outputs;
        this.wallMillis = wallMillis;
        for (FixSessionConfig s : config.sessions()) {
            sessionConfigs.put(s.senderCompId(), s);
        }
        this.nextOrderId = ((long) config.gatewayId() << 40) + 1;
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", config.port()));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int port() {
        return server.socket().getLocalPort();
    }

    @Override
    public String roleName() {
        return "clob-fix-gateway";
    }

    @Override
    public void close() throws IOException {
        for (FixSession s : sessions) {
            s.channel.close();
        }
        server.close();
        selector.close();
    }

    @Override
    public int doWork() throws IOException {
        int work = 0;
        if (selector.selectNow() > 0) {
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isAcceptable()) {
                    accept();
                } else if (key.isReadable()) {
                    read((FixSession) key.attachment());
                }
                work++;
            }
        }
        work += outputs.poll((type, buffer, index, length) -> {
            event.read(buffer, index);
            onEngineEvent();
        }, 256);
        long now = wallMillis.getAsLong();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            FixSession s = sessions.get(i);
            timers(s, now);
            try {
                s.flush();
            } catch (IOException e) {
                s.closing = true;
            }
            if (s.closing) {
                disconnect(s);
            }
        }
        return work;
    }

    // ------------------------------------------------------------------ IO

    private void accept() throws IOException {
        SocketChannel ch = server.accept();
        if (ch == null) {
            return;
        }
        ch.configureBlocking(false);
        ch.socket().setTcpNoDelay(true);
        FixSession s = new FixSession(ch, wallMillis.getAsLong());
        ch.register(selector, SelectionKey.OP_READ, s);
        sessions.add(s);
    }

    private void read(FixSession s) {
        try {
            if (s.channel.read(s.in) < 0) {
                s.closing = true;
                return;
            }
        } catch (IOException e) {
            s.closing = true;
            return;
        }
        byte[] b = s.in.array();
        int start = 0;
        int end = s.in.position();
        while (!s.closing && start < end) {
            int len = FixParser.frame(b, start, end - start);
            if (len < 0) {
                s.closing = true; // garbage on the wire
                return;
            }
            if (len == 0) {
                break;
            }
            String error = parser.parse(b, start, len);
            if (error != null) {
                s.closing = true; // garbled: FIX says drop it; we drop the connection to stay simple
                return;
            }
            onMessage(s);
            start += len;
        }
        s.in.limit(end).position(start);
        s.in.compact();
    }

    private void disconnect(FixSession s) {
        try {
            s.flush();
            s.channel.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        sessions.remove(s);
        if (s.loggedOn && s.config.cancelOnDisconnect()) {
            int len = InputCodec.massCancel(cmd, 0, MassCancelScope.USER, s.config.userId(), s.config.userId(),
                    CancelSource.DISCONNECT);
            submit(len);
        }
        s.loggedOn = false;
    }

    // ------------------------------------------------------------------ session layer

    private void onMessage(FixSession s) {
        long now = wallMillis.getAsLong();
        s.lastInMillis = now;
        String msgType = parser.str(35);
        long seq;
        try {
            seq = parser.asLong(34);
        } catch (NumberFormatException e) {
            logout(s, "MsgSeqNum missing");
            return;
        }
        if (!s.loggedOn) {
            if (!"A".equals(msgType)) {
                s.closing = true;
                return;
            }
            onLogon(s, seq, now);
            return;
        }
        if ("4".equals(msgType) && !parser.is(123, "Y")) {
            s.expectedIn = parser.asLong(36); // SequenceReset-Reset: ignores MsgSeqNum
            return;
        }
        if (seq > s.expectedIn) {
            if (!s.resendRequested) {
                s.resendRequested = true;
                send(s, begin("2", false).add(7, s.expectedIn).add(16, 0));
            }
            return; // dropped; the counterparty resends from expectedIn
        }
        if (seq < s.expectedIn) {
            if (!parser.is(43, "Y")) {
                logout(s, "MsgSeqNum too low, expecting " + s.expectedIn + " but received " + seq);
            }
            return;
        }
        s.expectedIn++;
        s.resendRequested = false;
        switch (msgType) {
            case "0" -> {
                if (s.pendingTestRequest != null && parser.is(112, s.pendingTestRequest)) {
                    s.pendingTestRequest = null;
                }
            }
            case "1" -> send(s, begin("0", false).add(112, parser.str(112)));
            case "2" -> onResendRequest(s);
            case "3" -> { } // session-level reject from the counterparty: nothing to do
            case "4" -> {
                long newSeq = parser.asLong(36);
                if (newSeq > s.expectedIn) {
                    s.expectedIn = newSeq;
                }
            }
            case "5" -> {
                send(s, begin("5", false));
                s.closing = true; // disconnect() then applies cancel-on-disconnect
            }
            default -> {
                if (!s.admit(now)) {
                    send(s, begin("j", true).add(45, seq).add(372, msgType).add(380, 99).add(58, "Throttled"));
                    return;
                }
                onApplication(s, msgType, seq);
            }
        }
    }

    private void onLogon(FixSession s, long seq, long now) {
        FixSessionConfig cfg = sessionConfigs.get(parser.str(49));
        if (cfg == null || !parser.is(56, config.compId()) || !parser.is(553, cfg.username())
                || !parser.is(554, cfg.password())) {
            s.closing = true; // unknown session or bad credentials: no Logout, as there is no session
            return;
        }
        for (FixSession other : sessions) {
            if (other != s && other.loggedOn && other.config == cfg) {
                s.closing = true; // one connection per session
                return;
            }
        }
        s.config = cfg;
        boolean reset = parser.is(141, "Y");
        if (reset) {
            s.resetSequences();
        }
        try {
            s.heartbeatMillis = Math.max(1, parser.asLong(108)) * 1000;
        } catch (NumberFormatException e) {
            s.closing = true;
            return;
        }
        if (seq < s.expectedIn) {
            s.closing = true;
            return;
        }
        s.loggedOn = true;
        s.tokens = cfg.maxMessagesPerSecond();
        s.tokensAtMillis = now;
        FixEncoder logon = begin("A", false).add(98, 0).add(108, s.heartbeatMillis / 1000);
        if (reset) {
            logon.add(141, 'Y');
        }
        send(s, logon);
        if (seq > s.expectedIn) {
            s.resendRequested = true;
            send(s, begin("2", false).add(7, s.expectedIn).add(16, 0));
        } else {
            s.expectedIn++;
        }
    }

    private void onResendRequest(FixSession s) {
        long begin = parser.asLong(7);
        long end = parser.asLong(16);
        long last = s.nextOut - 1;
        if (end == 0 || end > last) {
            end = last;
        }
        long seq = begin;
        while (seq <= end) {
            int i = (int) (seq - 1);
            if (i < s.sentTypes.size() && s.sentTypes.get(i) != null) {
                enc.begin(s.sentTypes.get(i), config.compId(), s.config.senderCompId(), seq, wallMillis.getAsLong(),
                        true, s.sentMillis.get(i)).addRaw(s.sentBodies.get(i));
                write(s, enc.finish());
                seq++;
            } else {
                long gapEnd = seq;
                while (gapEnd <= end && ((int) (gapEnd - 1) >= s.sentTypes.size() || s.sentTypes.get((int) (gapEnd - 1)) == null)) {
                    gapEnd++;
                }
                enc.begin("4", config.compId(), s.config.senderCompId(), seq, wallMillis.getAsLong(), true,
                        wallMillis.getAsLong()).add(123, 'Y').add(36, gapEnd);
                write(s, enc.finish());
                seq = gapEnd;
            }
        }
    }

    private void timers(FixSession s, long now) {
        if (!s.loggedOn) {
            if (now - s.connectedMillis > config.logonTimeoutMillis()) {
                s.closing = true;
            }
            return;
        }
        if (now - s.lastOutMillis >= s.heartbeatMillis) {
            send(s, begin("0", false));
        }
        if (s.pendingTestRequest == null && now - s.lastInMillis >= s.heartbeatMillis * 6 / 5) {
            s.pendingTestRequest = "TEST-" + now;
            s.testRequestSentMillis = now;
            send(s, begin("1", false).add(112, s.pendingTestRequest));
        } else if (s.pendingTestRequest != null && now - s.testRequestSentMillis >= s.heartbeatMillis) {
            logout(s, "Heartbeat timeout");
        }
    }

    private void logout(FixSession s, String text) {
        send(s, begin("5", false).add(58, text));
        s.closing = true;
    }

    private boolean pendingStore;
    private String pendingType;
    private int pendingMark;

    /**
     * Starts a message body. The body is built after a placeholder header; {@link #send} re-encodes it
     * with the session's header and sequence number. Application messages ({@code store}) are kept
     * for ResendRequest; admin messages are gap-filled instead.
     */
    private FixEncoder begin(String msgType, boolean store) {
        pendingStore = store;
        pendingType = msgType;
        enc.begin(msgType, "", "", 0, 0, false, 0);
        pendingMark = enc.mark();
        return enc;
    }

    private void send(FixSession s, FixEncoder unused) {
        byte[] body = enc.bodySince(pendingMark);
        long now = wallMillis.getAsLong();
        long seq = s.nextOut++;
        enc.begin(pendingType, config.compId(), s.config == null ? "?" : s.config.senderCompId(), seq, now, false, 0);
        enc.addRaw(body);
        write(s, enc.finish());
        s.store(seq, pendingStore ? pendingType : null, pendingStore ? body : null, now);
    }

    private void write(FixSession s, int length) {
        s.queue(enc.bytes(), length);
        s.lastOutMillis = wallMillis.getAsLong();
    }

    // ------------------------------------------------------------------ application messages

    private void onApplication(FixSession s, String msgType, long seq) {
        switch (msgType) {
            case "D" -> onNewOrderSingle(s, seq);
            case "F" -> onCancelRequest(s, seq);
            case "G" -> onReplaceRequest(s, seq);
            case "q" -> onMassCancel(s, seq);
            default -> send(s, begin("j", true).add(45, seq).add(372, msgType).add(380, 3)
                    .add(58, "Unsupported message type"));
        }
    }

    private boolean require(FixSession s, long seq, String msgType, int... tags) {
        for (int tag : tags) {
            if (!parser.has(tag)) {
                send(s, begin("3", false).add(45, seq).add(371, tag).add(372, msgType).add(373, 1)
                        .add(58, "Required tag missing"));
                return false;
            }
        }
        return true;
    }

    private Instrument instrument() {
        String tenor = parser.has(FixTags.TENOR) ? parser.str(FixTags.TENOR) : "SPOT";
        return refData.instrument(parser.str(55), tenor);
    }

    private void onNewOrderSingle(FixSession s, long seq) {
        if (!require(s, seq, "D", 11, 54, 38, 55, 1)) {
            return;
        }
        String clOrdId = parser.str(11);
        Instrument instrument = instrument();
        if (s.clOrdIds.containsKey(clOrdId)) {
            rejectUnsent(s, clOrdId, 6, "Duplicate ClOrdID");
            return;
        }
        if (instrument == null) {
            rejectUnsent(s, clOrdId, 1, "Unknown symbol/tenor");
            return;
        }
        NewOrderCommand c = newOrder.reset();
        try {
            c.orderId = nextOrderId++;
            c.instrumentId = instrument.id();
            c.accountId = (int) parser.asLong(1);
            c.userId = s.config.userId();
            c.side = side(parser.asChar(54));
            c.qty = parser.decimal(38, 0);
            c.type = orderType();
            c.tif = tif(parser.has(59) ? parser.asChar(59) : '0');
            if (c.type == OrderType.LIMIT || c.type == OrderType.ICEBERG) {
                c.price = parser.decimal(44, instrument.pricePrecision());
            }
            if (parser.has(111)) {
                c.maxShow = parser.decimal(111, 0);
                if (c.type == OrderType.LIMIT && c.maxShow < c.qty) {
                    c.type = OrderType.ICEBERG;
                }
            }
            if (c.type == OrderType.PEGGED) {
                long pegType = parser.has(1094) ? parser.asLong(1094) : 5;
                c.pegReference = pegType == 2 ? PegReference.MID : PegReference.NEAR_TOUCH;
            }
            c.discretion = parser.has(FixTags.DISCRETION) ? parser.asLong(FixTags.DISCRETION) : 0;
            c.fixingEventId = parser.has(FixTags.FIXING_EVENT) ? (int) parser.asLong(FixTags.FIXING_EVENT) : -1;
            if (c.tif == TimeInForce.GTT) {
                c.expireTime = FixTime.parseNanos(parser.str(126));
            }
        } catch (RuntimeException e) {
            nextOrderId--;
            rejectUnsent(s, clOrdId, 99, "Invalid field: " + e.getMessage());
            return;
        }
        if (c.side == null || c.type == null || c.tif == null) {
            nextOrderId--;
            rejectUnsent(s, clOrdId, 99, "Unsupported Side, OrdType or TimeInForce");
            return;
        }
        GatewayOrder g = new GatewayOrder(c.orderId, s, clOrdId, instrument, c.accountId, parser.asChar(54),
                parser.has(40) ? parser.asChar(40) : '2', parser.has(59) ? parser.asChar(59) : '0', c.qty, c.price);
        orders.put(g.orderId, g);
        s.clOrdIds.put(clOrdId, g.orderId);
        submit(InputCodec.newOrder(cmd, 0, c));
    }

    private OrderType orderType() {
        if (parser.has(FixTags.ORDER_TYPE)) {
            return OrderType.valueOf(parser.str(FixTags.ORDER_TYPE));
        }
        return switch (parser.has(40) ? parser.asChar(40) : '2') {
            case '1' -> OrderType.MARKET;
            case '2' -> OrderType.LIMIT;
            case 'P' -> OrderType.PEGGED;
            default -> null;
        };
    }

    private static Side side(char c) {
        return c == '1' ? Side.BUY : c == '2' ? Side.SELL : null;
    }

    private static TimeInForce tif(char c) {
        return switch (c) {
            case '0' -> TimeInForce.DAY;
            case '1' -> TimeInForce.GTC;
            case '3' -> TimeInForce.IOC;
            case '4' -> TimeInForce.FOK;
            case '6' -> TimeInForce.GTT;
            default -> null;
        };
    }

    private GatewayOrder lookup(FixSession s, String clOrdId) {
        Long id = clOrdId == null ? null : s.clOrdIds.get(clOrdId);
        return id == null ? null : orders.get(id);
    }

    private void onCancelRequest(FixSession s, long seq) {
        if (!require(s, seq, "F", 11, 41)) {
            return;
        }
        GatewayOrder g = lookup(s, parser.str(41));
        if (g == null) {
            cancelRejectUnknown(s, parser.str(11), parser.str(41), 1);
            return;
        }
        g.pendingClOrdId = parser.str(11);
        g.pendingType = RequestType.CANCEL;
        s.clOrdIds.put(g.pendingClOrdId, g.orderId);
        submit(InputCodec.cancel(cmd, 0, g.orderId, s.config.userId(), CancelSource.PARTICIPANT));
    }

    private void onReplaceRequest(FixSession s, long seq) {
        if (!require(s, seq, "G", 11, 41, 38)) {
            return;
        }
        GatewayOrder g = lookup(s, parser.str(41));
        if (g == null) {
            cancelRejectUnknown(s, parser.str(11), parser.str(41), 2);
            return;
        }
        ReplaceCommand r = replace.reset();
        try {
            r.orderId = g.orderId;
            r.userId = s.config.userId();
            r.qty = parser.decimal(38, 0);
            r.price = parser.has(44) ? parser.decimal(44, g.instrument.pricePrecision()) : g.price;
            r.maxShow = parser.has(111) ? parser.decimal(111, 0) : NewOrderCommand.FULLY_VISIBLE;
            r.discretion = parser.has(FixTags.DISCRETION) ? parser.asLong(FixTags.DISCRETION) : 0;
        } catch (RuntimeException e) {
            cancelRejectUnknown(s, parser.str(11), parser.str(41), 2);
            return;
        }
        g.pendingClOrdId = parser.str(11);
        g.pendingType = RequestType.REPLACE;
        s.clOrdIds.put(g.pendingClOrdId, g.orderId);
        submit(InputCodec.replace(cmd, 0, r));
    }

    private void onMassCancel(FixSession s, long seq) {
        if (!require(s, seq, "q", 11, 530)) {
            return;
        }
        boolean byAccount = parser.has(1);
        int scopeId = byAccount ? (int) parser.asLong(1) : s.config.userId();
        boolean allowed = !byAccount || refData.isEntitled(s.config.userId(), scopeId);
        send(s, begin("r", true).add(37, "MC-" + (++nextMassCancelId)).add(11, parser.str(11))
                .add(530, parser.str(530)).add(531, allowed ? '7' : '0')
                .add(58, allowed ? null : "Not entitled to account"));
        if (allowed) {
            submit(InputCodec.massCancel(cmd, 0, byAccount ? MassCancelScope.ACCOUNT : MassCancelScope.USER, scopeId,
                    s.config.userId(), CancelSource.PARTICIPANT));
        }
    }

    private void submit(int length) {
        while (!pipeline.submit(cmd, 0, length)) {
            Thread.onSpinWait();
        }
    }

    private void rejectUnsent(FixSession s, String clOrdId, int ordRejReason, String text) {
        send(s, begin("8", true).add(37, "NONE").add(11, clOrdId).add(17, "X" + (++nextExecId)).add(150, '8')
                .add(39, '8').add(55, parser.str(55)).add(54, parser.has(54) ? parser.asChar(54) : '1')
                .add(151, 0).add(14, 0).add(6, 0).add(103, ordRejReason).add(58, text));
    }

    private void cancelRejectUnknown(FixSession s, String clOrdId, String origClOrdId, int responseTo) {
        send(s, begin("9", true).add(37, "NONE").add(11, clOrdId).add(41, origClOrdId).add(39, '8')
                .add(434, responseTo).add(102, 1).add(58, "Unknown order"));
    }

    // ------------------------------------------------------------------ engine output → FIX

    private void onEngineEvent() {
        switch (event.type) {
            case OutputEncoder.ACCEPTED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.leaves = g.qty;
                    exec(g, '0', g.clOrdId, null).finish(g);
                }
            }
            case OutputEncoder.REJECTED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.terminal = true;
                    RejectReason r = RejectReason.values()[event.reason];
                    exec(g, '8', g.clOrdId, null).status('8')
                            .add(103, r == RejectReason.UNKNOWN_INSTRUMENT ? 1 : r == RejectReason.DUPLICATE_ORDER_ID ? 6 : 99)
                            .add(58, r.name()).finish(g);
                    forget(g);
                }
            }
            case OutputEncoder.TRADE -> {
                onTradeSide(event.aggressorOrderId, event.aggressorLeaves, 'A');
                onTradeSide(event.passiveOrderId, event.passiveLeaves, 'P');
            }
            case OutputEncoder.FIXING_MATCHED -> {
                onFixingMatchSide(event.aggressorOrderId, event.aggressorLeaves);
                onFixingMatchSide(event.passiveOrderId, event.passiveLeaves);
            }
            case OutputEncoder.CANCELLED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.leaves = 0;
                    g.terminal = true;
                    boolean requested = g.pendingType == RequestType.CANCEL
                            && CancelReason.values()[event.reason] == CancelReason.PARTICIPANT;
                    exec(g, '4', requested ? g.pendingClOrdId : g.clOrdId, requested ? g.clOrdId : null).status('4')
                            .add(58, CancelReason.values()[event.reason].name()).finish(g);
                    forget(g);
                }
            }
            case OutputEncoder.REPLACED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    String old = g.clOrdId;
                    g.qty = event.qty;
                    g.price = event.price;
                    g.leaves = event.leaves;
                    if (g.pendingType == RequestType.REPLACE) {
                        g.clOrdId = g.pendingClOrdId;
                        g.pendingType = null;
                    }
                    exec(g, '5', g.clOrdId, old).finish(g);
                }
            }
            case OutputEncoder.REQUEST_REJECTED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    RequestType type = RequestType.values()[event.requestType];
                    RequestRejectReason r = RequestRejectReason.values()[event.reason];
                    send(g.session, begin("9", true).add(37, g.orderId).add(11, g.pendingClOrdId == null ? "NONE" : g.pendingClOrdId)
                            .add(41, g.clOrdId).add(39, g.ordStatus()).add(434, type == RequestType.CANCEL ? 1 : 2)
                            .add(102, r == RequestRejectReason.UNKNOWN_ORDER ? 1 : r == RequestRejectReason.TOO_LATE ? 0 : 99)
                            .add(58, r.name()));
                    if (g.pendingType == type) {
                        g.pendingType = null;
                    }
                }
            }
            case OutputEncoder.REQUEST_QUEUED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    char pending = RequestType.values()[event.requestType] == RequestType.CANCEL ? '6' : 'E';
                    exec(g, pending, g.pendingClOrdId, g.clOrdId).status(pending).finish(g);
                }
            }
            case OutputEncoder.REPRICED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.price = event.price;
                    exec(g, 'D', g.clOrdId, null).add(378, 3).finish(g);
                }
            }
            case OutputEncoder.SUSPENDED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    exec(g, 'D', g.clOrdId, null).add(378, 99).add(58, "SUSPENDED").finish(g);
                }
            }
            case OutputEncoder.JOINED_RACE -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    exec(g, 'I', g.clOrdId, null).add(FixTags.RACE_ID, event.raceId).add(58, "RACE").finish(g);
                }
            }
            case OutputEncoder.SESSION_STATE -> {
                Instrument instrument = refData.instrument(event.instrumentId);
                SessionState state = SessionState.values()[event.sessionState];
                int status = switch (state) {
                    case OPEN -> 2;
                    case HALTED -> 1;
                    case CLOSED -> 3;
                    case PRE_OPEN -> 4;
                };
                for (FixSession s : sessions) {
                    if (s.loggedOn) {
                        send(s, begin("h", true).add(336, "CLOB").add(340, status)
                                .add(FixTags.SYMBOL, instrument.symbol()).add(FixTags.TENOR, instrument.tenor()));
                    }
                }
            }
            default -> { } // RACE_STARTED: market-wide, not per order
        }
    }

    private void onTradeSide(long orderId, long leaves, char role) {
        GatewayOrder g = orders.get(orderId);
        if (g == null) {
            return;
        }
        if (leaves < 0) {
            g.pendingFixing -= event.qty; // a fixing match being priced; leaves already known
        } else {
            g.leaves = leaves;
        }
        g.fill(event.qty, event.price, event.priceDecimals);
        exec(g, 'F', g.clOrdId, null).add(32, event.qty).addDecimal(31, event.price, event.priceDecimals)
                .add(FixTags.TRADE_ID, "T" + event.tradeId + role).finish(g);
        if (g.leaves == 0 && g.pendingFixing == 0) {
            g.terminal = true;
            forget(g);
        }
    }

    private void onFixingMatchSide(long orderId, long leaves) {
        GatewayOrder g = orders.get(orderId);
        if (g == null) {
            return;
        }
        g.leaves = leaves;
        g.pendingFixing += event.qty;
        exec(g, 'I', g.clOrdId, null).add(FixTags.FIXING_MATCHED_QTY, event.qty).add(58, "FIXING MATCHED").finish(g);
    }

    private void forget(GatewayOrder g) {
        if (g.pendingFixing == 0) {
            orders.remove(g.orderId);
        }
    }

    /** Execution report builder with the fields every report carries. */
    private ExecBuilder exec(GatewayOrder g, char execType, String clOrdId, String origClOrdId) {
        ExecBuilder b = execBuilder;
        b.encoder = begin("8", true).add(37, g.orderId).add(11, clOrdId).add(41, origClOrdId)
                .add(17, "E" + (++nextExecId)).add(150, execType);
        b.status = 0;
        return b;
    }

    private final ExecBuilder execBuilder = new ExecBuilder();

    private final class ExecBuilder {
        FixEncoder encoder;
        char status;

        ExecBuilder status(char s) {
            status = s;
            return this;
        }

        ExecBuilder add(int tag, long v) {
            encoder.add(tag, v);
            return this;
        }

        ExecBuilder add(int tag, String v) {
            encoder.add(tag, v);
            return this;
        }

        ExecBuilder addDecimal(int tag, long v, int decimals) {
            encoder.addDecimal(tag, v, decimals);
            return this;
        }

        void finish(GatewayOrder g) {
            encoder.add(39, status != 0 ? status : g.ordStatus()).add(1, g.account).add(55, g.instrument.symbol())
                    .add(FixTags.TENOR, g.instrument.tenor()).add(54, g.side).add(38, g.qty).add(40, g.ordType)
                    .add(59, g.tif);
            if (g.price != 0) {
                encoder.addDecimal(44, g.price, g.instrument.pricePrecision());
            }
            encoder.add(151, g.leaves).add(14, g.cumQty).addDouble(6, g.avgPx()).addTime(60, wallMillis.getAsLong());
            send(g.session, encoder);
        }
    }
}
