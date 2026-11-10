package clob.fix;

import clob.common.Side;
import clob.engine.CancelSource;
import clob.engine.MassCancelScope;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import clob.engine.RequestType;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.marketdata.BookView;
import clob.marketdata.MarketDataService;
import clob.marketdata.SnapshotCodec;
import clob.marketdata.UdpPublisher;
import clob.sequencer.InputCodec;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * FIX 4.4 order-entry acceptor. Runs on its own thread (an Agrona {@link Agent}): accepts TCP
 * connections, runs the FIX session layer, turns application messages into engine inputs for the
 * {@link Pipeline}, and hands engine output to the {@link OrderRegistry}, which owns orders and
 * reports. See {@code docs/FIX-RoE.md} for the rules of engagement.
 *
 * <p>Session layer: Logon with Username/Password (553/554) and ResetSeqNumFlag (141), Heartbeat,
 * TestRequest, ResendRequest (application messages resent with PossDupFlag; admin messages replaced
 * by SequenceReset-GapFill), SequenceReset (gap fill and reset), Logout, and inbound gap detection.
 * Sequence numbers, the resend store and order ownership belong to the logical session, so they
 * survive a disconnect and, with a store directory, a restart.
 *
 * <p>This agent never blocks. If the inbound ring is full the input waits in a small backlog and the
 * gateway stops reading sockets until it drains, but keeps consuming engine output, so the
 * gateway/engine/sequencer cycle of bounded queues cannot deadlock.
 */
public final class FixGateway implements Agent, AutoCloseable {
    private final FixGatewayConfig config;
    private final RefData refData;
    private final Pipeline pipeline;
    private final OutputRing outputs;
    private final LongSupplier wallMillis;
    private final OrderRegistry registry;
    private final Map<String, FixSessionConfig> sessionConfigs = new HashMap<>();
    private final Selector selector;
    private final ServerSocketChannel server;
    private final List<FixSession> sessions = new ArrayList<>();
    private final FixParser parser = new FixParser();
    private final UnsafeBuffer cmd = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
    private final UnsafeBuffer backlogView = new UnsafeBuffer(new byte[0]);
    private final ArrayDeque<byte[]> backlog = new ArrayDeque<>();
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private long nextMassCancelId;
    private long nextRejectId;

    private volatile boolean stopIngress;
    private volatile boolean ingressStopped;
    private volatile int backlogSize;
    private final AtomicLong mdDropped = new AtomicLong();

    private final MarketDataService marketData;
    private final OneToOneRingBuffer mdRing;
    private final UnsafeBuffer mdScratch = new UnsafeBuffer(ByteBuffer.allocateDirect(1 << 16));
    private final BookView mdView = SnapshotCodec.newView();
    private final Long2ObjectHashMap<MdSubscription> mdSubscriptions = new Long2ObjectHashMap<>();
    private long nextSubscriptionId;

    private UdpPublisher udp;
    private int udpRefreshCycles;

    private record MdSubscription(FixSession session, String mdReqId, Instrument instrument, boolean overUdp) {}

    public FixGateway(FixGatewayConfig config, RefData refData, Pipeline pipeline, OutputRing outputs,
            LongSupplier wallMillis) throws IOException {
        this(config, refData, pipeline, outputs, null, wallMillis);
    }

    /** With market data: {@code marketData} must be a follower of {@code pipeline}. */
    public FixGateway(FixGatewayConfig config, RefData refData, Pipeline pipeline, OutputRing outputs,
            MarketDataService marketData, LongSupplier wallMillis) throws IOException {
        this(config, refData, pipeline, outputs, marketData, wallMillis,
                new OrderRegistry(config, refData, wallMillis, OrderRegistry.Mode.LIVE));
    }

    /** With order ownership already rebuilt from history. */
    public FixGateway(FixGatewayConfig config, RefData refData, Pipeline pipeline, OutputRing outputs,
            MarketDataService marketData, LongSupplier wallMillis, OrderRegistry registry) throws IOException {
        this.marketData = marketData;
        this.mdRing = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect((1 << 20)
                + RingBufferDescriptor.TRAILER_LENGTH)));
        this.nextSubscriptionId = ((long) config.gatewayId() << 40) + 1;
        this.config = config;
        this.refData = refData;
        this.pipeline = pipeline;
        this.outputs = outputs;
        this.wallMillis = wallMillis;
        this.registry = registry;
        for (FixSessionConfig s : config.sessions()) {
            sessionConfigs.put(s.senderCompId(), s);
        }
        registry.goLive();
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        try {
            server.bind(new InetSocketAddress(config.bindHost(), config.port()));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) {
            server.close();
            selector.close();
            throw e;
        }
    }

    public int port() {
        return server.socket().getLocalPort();
    }

    public OrderRegistry registry() {
        return registry;
    }

    /** Allows UDP market data subscriptions, re-sent at least every {@code refreshCycles} instrument cycles. */
    public void enableUdp(UdpPublisher publisher, int refreshCycles) {
        udp = publisher;
        udpRefreshCycles = refreshCycles;
    }

    /** Snapshots dropped because the gateway's market data ring was full (the next cycle replaces them). */
    public long marketDataDropped() {
        return mdDropped.get();
    }

    @Override
    public String roleName() {
        return "clob-fix-gateway";
    }

    /**
     * Staged shutdown, any thread: stop reading and accepting, and submit cancel-on-disconnect for every
     * connected session while the pipeline is still running. Sockets stay open so the resulting reports
     * can be delivered. {@link #ingressStopped()} turns true once all of that has reached the pipeline.
     */
    public void stopIngress() {
        stopIngress = true;
    }

    public boolean ingressStopped() {
        return ingressStopped;
    }

    @Override
    public void close() throws IOException {
        registry.flushStores();
        for (FixSession s : sessions) {
            try {
                s.flush();
            } catch (IOException ignored) {
                // closing anyway
            }
            s.channel.close();
        }
        server.close();
        selector.close();
        registry.close();
    }

    @Override
    public int doWork() throws IOException {
        int work = 0;
        boolean clear = drainBacklog();
        if (stopIngress) {
            if (clear && !ingressStopped) {
                for (FixSession s : sessions) {
                    submitDisconnectCancel(s);
                }
                if (backlog.isEmpty()) {
                    ingressStopped = true;
                }
            }
        } else if (clear) {
            work += readSockets();
        }
        work += outputs.poll((type, buffer, index, length) -> registry.onOutput(buffer, index), 256);
        work += mdRing.read((type, buffer, index, length) -> onSnapshot(buffer, index), 256);
        registry.flushStores(); // the store must hold a report before the client can see it
        long now = wallMillis.getAsLong();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            FixSession s = sessions.get(i);
            if (!stopIngress) {
                timers(s, now);
            }
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

    private int readSockets() throws IOException {
        int work = 0;
        for (int i = 0; i < sessions.size() && backlog.isEmpty(); i++) {
            FixSession s = sessions.get(i);
            if (s.unparsed) {
                parse(s);
                work++;
            }
        }
        if (!backlog.isEmpty() || selector.selectNow() == 0) {
            return work;
        }
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            if (key.isAcceptable()) {
                accept();
            } else if (key.isReadable() && backlog.isEmpty()) {
                read((FixSession) key.attachment());
            }
            work++;
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
        parse(s);
    }

    /** Handles complete messages in the connection's buffer, stopping early if the inbound ring is full. */
    private void parse(FixSession s) {
        byte[] b = s.in.array();
        int start = 0;
        int end = s.in.position();
        s.unparsed = false;
        while (!s.closing && start < end) {
            if (!backlog.isEmpty()) {
                s.unparsed = true;
                break;
            }
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
            try {
                onMessage(s);
            } catch (RuntimeException e) {
                // A malformed message must not reach the shared gateway loop: only this connection pays.
                if (s.logical != null) {
                    logout(s, "Invalid message");
                } else {
                    s.closing = true;
                }
                return;
            }
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
        for (Long id : s.mdRequests.values()) {
            unsubscribeMd(id);
        }
        submitDisconnectCancel(s);
        s.loggedOn = false;
        if (s.logical != null && s.logical.connection == s) {
            s.logical.connection = null;
        }
    }

    private void unsubscribeMd(long id) {
        MdSubscription sub = mdSubscriptions.remove(id);
        marketData.unsubscribe(id);
        if (sub != null && sub.overUdp() && udp != null) {
            udp.unregister(id);
        }
    }

    private void submitDisconnectCancel(FixSession s) {
        if (s.loggedOn && s.config.cancelOnDisconnect() && !s.disconnectCancelSent) {
            s.disconnectCancelSent = true;
            int len = InputCodec.massCancel(cmd, 0, MassCancelScope.USER, s.config.userId(), s.config.userId(),
                    CancelSource.DISCONNECT);
            submit(len);
        }
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
            if (s.logical == null) {
                s.closing = true;
            } else {
                logout(s, "MsgSeqNum missing");
            }
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
        if (!parser.is(49, s.config.senderCompId()) || !parser.is(56, config.compId())) {
            logout(s, "SenderCompID or TargetCompID does not match the session");
            return;
        }
        LogicalSession ls = s.logical;
        if ("4".equals(msgType) && !parser.is(123, "Y")) {
            long newSeq = parser.asLong(36); // SequenceReset-Reset: ignores MsgSeqNum
            if (newSeq < 1) {
                logout(s, "Invalid NewSeqNo");
                return;
            }
            ls.expectedIn(newSeq);
            return;
        }
        if (seq > ls.expectedIn()) {
            if (!s.resendRequested) {
                s.resendRequested = true;
                begin("2", false).add(7, ls.expectedIn()).add(16, 0);
                registry.send(ls);
            }
            return; // dropped; the counterparty resends from expectedIn
        }
        if (seq < ls.expectedIn()) {
            if (!parser.is(43, "Y")) {
                logout(s, "MsgSeqNum too low, expecting " + ls.expectedIn() + " but received " + seq);
            }
            return;
        }
        ls.expectedIn(ls.expectedIn() + 1);
        s.resendRequested = false;
        switch (msgType) {
            case "0" -> {
                if (s.pendingTestRequest != null && parser.is(112, s.pendingTestRequest)) {
                    s.pendingTestRequest = null;
                }
            }
            case "1" -> {
                begin("0", false).add(112, parser.str(112));
                registry.send(ls);
            }
            case "2" -> onResendRequest(s);
            case "3" -> { } // session-level reject from the counterparty: nothing to do
            case "4" -> {
                long newSeq = parser.asLong(36);
                if (newSeq > ls.expectedIn()) {
                    ls.expectedIn(newSeq);
                }
            }
            case "5" -> {
                begin("5", false);
                registry.send(ls);
                s.closing = true; // disconnect() then applies cancel-on-disconnect
            }
            default -> {
                if (!s.admit(now)) {
                    begin("j", true).add(45, seq).add(372, msgType).add(380, 99).add(58, "Throttled");
                    registry.send(ls);
                    return;
                }
                onApplication(s, msgType, seq);
            }
        }
    }

    private void onLogon(FixSession s, long seq, long now) {
        String sender = parser.str(49);
        FixSessionConfig cfg = sender == null ? null : sessionConfigs.get(sender);
        if (cfg == null || !parser.is(56, config.compId()) || !parser.is(553, cfg.username())
                || !cfg.passwordMatches(parser.str(554))) {
            s.closing = true; // unknown session or bad credentials: no Logout, as there is no session
            return;
        }
        LogicalSession ls = registry.session(sender);
        if (ls.connected()) {
            s.closing = true; // one connection per session
            return;
        }
        long heartbeat;
        try {
            heartbeat = parser.asLong(108);
        } catch (NumberFormatException e) {
            s.closing = true;
            return;
        }
        if (heartbeat < 1 || heartbeat > 3600) {
            s.closing = true;
            return;
        }
        boolean reset = parser.is(141, "Y");
        if (reset) {
            ls.store.reset();
        }
        if (seq < ls.expectedIn()) {
            s.closing = true;
            return;
        }
        s.config = cfg;
        s.logical = ls;
        ls.connection = s;
        s.heartbeatMillis = heartbeat * 1000;
        s.loggedOn = true;
        s.tokens = cfg.maxMessagesPerSecond();
        s.tokensAtMillis = now;
        FixEncoder logon = begin("A", false).add(98, 0).add(108, heartbeat);
        if (reset) {
            logon.add(141, 'Y');
        }
        registry.send(ls);
        if (seq > ls.expectedIn()) {
            s.resendRequested = true;
            begin("2", false).add(7, ls.expectedIn()).add(16, 0);
            registry.send(ls);
        } else {
            ls.expectedIn(ls.expectedIn() + 1);
        }
    }

    private void onResendRequest(FixSession s) {
        LogicalSession ls = s.logical;
        long begin = parser.asLong(7);
        long end = parser.asLong(16);
        long last = ls.store.nextOut() - 1;
        if (begin < 1 || end < 0 || end != 0 && end < begin) {
            logout(s, "Invalid ResendRequest range");
            return;
        }
        if (end == 0 || end > last) {
            end = last;
        }
        FixEncoder enc = registry.encoder();
        long nowMillis = wallMillis.getAsLong();
        long seq = begin;
        while (seq <= end) {
            FixStore.Entry e = ls.store.get(seq);
            if (e != null) {
                enc.begin(e.msgType(), config.compId(), s.config.senderCompId(), seq, nowMillis, true, e.millis())
                        .addRaw(e.body());
                write(s, enc.finish());
                seq++;
            } else {
                long gapEnd = seq;
                while (gapEnd <= end && ls.store.get(gapEnd) == null) {
                    gapEnd++;
                }
                enc.begin("4", config.compId(), s.config.senderCompId(), seq, nowMillis, true, nowMillis)
                        .add(123, 'Y').add(36, gapEnd);
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
            begin("0", false);
            registry.send(s.logical);
        }
        if (s.pendingTestRequest == null && now - s.lastInMillis >= s.heartbeatMillis * 6 / 5) {
            s.pendingTestRequest = "TEST-" + now;
            s.testRequestSentMillis = now;
            begin("1", false).add(112, s.pendingTestRequest);
            registry.send(s.logical);
        } else if (s.pendingTestRequest != null && now - s.testRequestSentMillis >= s.heartbeatMillis) {
            logout(s, "Heartbeat timeout");
        }
    }

    private void logout(FixSession s, String text) {
        begin("5", false).add(58, text);
        registry.send(s.logical);
        s.closing = true;
    }

    private FixEncoder begin(String msgType, boolean store) {
        return registry.begin(msgType, store);
    }

    private void write(FixSession s, int length) {
        s.queue(registry.encoder().bytes(), length);
        s.lastOutMillis = wallMillis.getAsLong();
    }

    // ------------------------------------------------------------------ application messages

    private void onApplication(FixSession s, String msgType, long seq) {
        switch (msgType) {
            case "D" -> onNewOrderSingle(s, seq);
            case "F" -> onCancelRequest(s, seq);
            case "G" -> onReplaceRequest(s, seq);
            case "q" -> onMassCancel(s, seq);
            case "V" -> onMarketDataRequest(s, seq);
            case "H" -> onOrderStatusRequest(s, seq);
            default -> {
                begin("j", true).add(45, seq).add(372, msgType).add(380, 3).add(58, "Unsupported message type");
                registry.send(s.logical);
            }
        }
    }

    private boolean require(FixSession s, long seq, String msgType, int... tags) {
        for (int tag : tags) {
            if (!parser.has(tag)) {
                begin("3", false).add(45, seq).add(371, tag).add(372, msgType).add(373, 1).add(58, "Required tag missing");
                registry.send(s.logical);
                return false;
            }
        }
        return true;
    }

    private Instrument instrument() {
        String tenor = parser.has(FixTags.TENOR) ? parser.str(FixTags.TENOR) : "SPOT";
        return refData.instrument(parser.str(55), tenor);
    }

    private boolean idTooLong(String clOrdId) {
        return clOrdId.length() > InputCodec.MAX_ID_LENGTH;
    }

    private void onNewOrderSingle(FixSession s, long seq) {
        if (!require(s, seq, "D", 11, 54, 38, 55, 1)) {
            return;
        }
        String clOrdId = parser.str(11);
        Instrument instrument = instrument();
        LogicalSession ls = s.logical;
        if (ls.clOrdIds.containsKey(clOrdId)) {
            rejectUnsent(s, clOrdId, 6, "Duplicate ClOrdID");
            return;
        }
        if (idTooLong(clOrdId)) {
            rejectUnsent(s, clOrdId, 99, "ClOrdID too long");
            return;
        }
        if (instrument == null) {
            rejectUnsent(s, clOrdId, 1, "Unknown symbol/tenor");
            return;
        }
        NewOrderCommand c = newOrder.reset();
        try {
            c.instrumentId = instrument.id();
            c.accountId = Math.toIntExact(parser.asLong(1));
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
            c.fixingEventId = parser.has(FixTags.FIXING_EVENT) ? Math.toIntExact(parser.asLong(FixTags.FIXING_EVENT)) : -1;
            if (c.tif == TimeInForce.GTT) {
                c.expireTime = FixTime.parseNanos(parser.str(126));
            }
        } catch (RuntimeException e) {
            rejectUnsent(s, clOrdId, 99, "Invalid field: " + e.getMessage());
            return;
        }
        if (c.side == null || c.type == null || c.tif == null) {
            rejectUnsent(s, clOrdId, 99, "Unsupported Side, OrdType or TimeInForce");
            return;
        }
        c.orderId = registry.nextOrderId();
        char ordType = parser.has(40) ? parser.asChar(40) : '2';
        registry.registerOrder(c.orderId, ls, clOrdId, instrument, c.accountId, parser.asChar(54), ordType,
                parser.has(59) ? parser.asChar(59) : '0', c.qty, c.price);
        int length = InputCodec.newOrder(cmd, 0, c);
        submit(InputCodec.withIdentity(cmd, 0, length, OrderRegistry.ascii(ls.config.senderCompId()),
                OrderRegistry.ascii(clOrdId), (byte) ordType));
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
        Long id = clOrdId == null ? null : s.logical.clOrdIds.get(clOrdId);
        return id == null ? null : registry.order(id);
    }

    /** Duplicate or over-long request ids are refused before any lookup can be disturbed (Q30). */
    private boolean badRequestId(FixSession s, String clOrdId, String origClOrdId, int responseTo) {
        String why = idTooLong(clOrdId) ? "ClOrdID too long" : s.logical.clOrdIds.containsKey(clOrdId) ? "Duplicate ClOrdID" : null;
        if (why == null) {
            return false;
        }
        begin("9", true).add(37, "NONE").add(11, clOrdId).add(41, origClOrdId).add(39, '8').add(434, responseTo)
                .add(102, 6).add(58, why);
        registry.send(s.logical);
        return true;
    }

    private void onCancelRequest(FixSession s, long seq) {
        if (!require(s, seq, "F", 11, 41)) {
            return;
        }
        String clOrdId = parser.str(11);
        if (badRequestId(s, clOrdId, parser.str(41), 1)) {
            return;
        }
        GatewayOrder g = lookup(s, parser.str(41));
        if (g == null) {
            cancelRejectUnknown(s, clOrdId, parser.str(41), 1);
            return;
        }
        long requestId = registry.nextRequestId();
        registry.registerRequest(requestId, s.logical, g, clOrdId, RequestType.CANCEL);
        int length = InputCodec.cancel(cmd, 0, g.orderId, s.config.userId(), CancelSource.PARTICIPANT, requestId);
        submit(InputCodec.withIdentity(cmd, 0, length, OrderRegistry.ascii(s.logical.config.senderCompId()),
                OrderRegistry.ascii(clOrdId), (byte) 0));
    }

    private void onReplaceRequest(FixSession s, long seq) {
        if (!require(s, seq, "G", 11, 41, 38)) {
            return;
        }
        String clOrdId = parser.str(11);
        if (badRequestId(s, clOrdId, parser.str(41), 2)) {
            return;
        }
        GatewayOrder g = lookup(s, parser.str(41));
        if (g == null) {
            cancelRejectUnknown(s, clOrdId, parser.str(41), 2);
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
            cancelRejectUnknown(s, clOrdId, parser.str(41), 2);
            return;
        }
        r.requestId = registry.nextRequestId();
        registry.registerRequest(r.requestId, s.logical, g, clOrdId, RequestType.REPLACE);
        int length = InputCodec.replace(cmd, 0, r);
        submit(InputCodec.withIdentity(cmd, 0, length, OrderRegistry.ascii(s.logical.config.senderCompId()),
                OrderRegistry.ascii(clOrdId), (byte) 0));
    }

    /** OrderStatusRequest (H): one order by ClOrdID, or every live order of the session with "*". */
    private void onOrderStatusRequest(FixSession s, long seq) {
        if (!require(s, seq, "H", 11)) {
            return;
        }
        String clOrdId = parser.str(11);
        if ("*".equals(clOrdId)) {
            registry.reportStatus(s.logical, null);
            return;
        }
        GatewayOrder g = lookup(s, clOrdId);
        if (g == null) {
            begin("8", true).add(37, "NONE").add(11, clOrdId).add(17, "X" + s.logical.config.senderCompId() + "-" + (++nextRejectId))
                    .add(150, 'I').add(39, '8').add(58, "Unknown order");
            registry.send(s.logical);
            return;
        }
        registry.reportStatus(s.logical, g);
    }

    private void onMassCancel(FixSession s, long seq) {
        if (!require(s, seq, "q", 11, 530)) {
            return;
        }
        boolean byAccount = parser.has(1);
        int scopeId = byAccount ? Math.toIntExact(parser.asLong(1)) : s.config.userId();
        boolean allowed = !byAccount || refData.isEntitled(s.config.userId(), scopeId);
        begin("r", true).add(37, "MC-" + (++nextMassCancelId)).add(11, parser.str(11))
                .add(530, parser.str(530)).add(531, allowed ? '7' : '0')
                .add(58, allowed ? null : "Not entitled to account");
        registry.send(s.logical);
        if (allowed) {
            submit(InputCodec.massCancel(cmd, 0, byAccount ? MassCancelScope.ACCOUNT : MassCancelScope.USER, scopeId,
                    s.config.userId(), CancelSource.PARTICIPANT));
        }
    }

    private void onMarketDataRequest(FixSession s, long seq) {
        if (!require(s, seq, "V", 262, 263)) {
            return;
        }
        String reqId = parser.str(262);
        char type = parser.asChar(263);
        if (type == '2') {
            Long id = s.mdRequests.remove(reqId);
            if (id != null) {
                unsubscribeMd(id);
            }
            return;
        }
        if (marketData == null) {
            begin("Y", true).add(262, reqId).add(281, '0').add(58, "No market data on this gateway");
            registry.send(s.logical);
            return;
        }
        Instrument instrument = parser.has(55) ? instrument() : null;
        boolean screened = parser.is(FixTags.SCREENED, "Y");
        int account = parser.has(1) ? Math.toIntExact(parser.asLong(1)) : 0;
        if (instrument == null) {
            begin("Y", true).add(262, reqId).add(281, '0').add(58, "Unknown symbol/tenor");
            registry.send(s.logical);
            return;
        }
        if (screened && !refData.isEntitled(s.config.userId(), account)) {
            begin("Y", true).add(262, reqId).add(281, '3').add(58, "Not entitled to account");
            registry.send(s.logical);
            return;
        }
        if (s.mdRequests.containsKey(reqId)) {
            begin("Y", true).add(262, reqId).add(281, '1').add(58, "Duplicate MDReqID");
            registry.send(s.logical);
            return;
        }
        int depth = parser.has(264) ? Math.toIntExact(parser.asLong(264)) : 0;
        long id = nextSubscriptionId++;
        s.mdRequests.put(reqId, id);
        if (parser.has(FixTags.UDP_PORT)) {
            if (udp == null) {
                s.mdRequests.remove(reqId);
                begin("Y", true).add(262, reqId).add(281, '0').add(58, "UDP market data is not enabled");
                registry.send(s.logical);
                return;
            }
            int port = Math.toIntExact(parser.asLong(FixTags.UDP_PORT));
            InetSocketAddress peer;
            try {
                peer = new InetSocketAddress(((InetSocketAddress) s.channel.getRemoteAddress()).getAddress(), port);
            } catch (IOException | IllegalArgumentException e) {
                s.mdRequests.remove(reqId);
                begin("Y", true).add(262, reqId).add(281, '0').add(58, "Invalid UDP port");
                registry.send(s.logical);
                return;
            }
            udp.register(id, peer);
            mdSubscriptions.put(id, new MdSubscription(s, reqId, instrument, true));
            marketData.subscribe(id, account, instrument.id(), depth, screened, udp, udpRefreshCycles);
            return;
        }
        mdSubscriptions.put(id, new MdSubscription(s, reqId, instrument, false));
        marketData.subscribe(id, account, instrument.id(), depth, screened, this::onMarketDataThread);
    }

    /**
     * Market data thread: encode and hand over to the gateway thread. Never spins: if the ring is full
     * this snapshot is dropped and the subscriber gets the next cycle's, which supersedes it.
     */
    private void onMarketDataThread(long subscriptionId, long boundary, BookView view) {
        synchronized (mdScratch) {
            int length = SnapshotCodec.encode(mdScratch, 0, subscriptionId, 0, boundary, view);
            if (!mdRing.write(1, mdScratch, 0, length)) {
                mdDropped.incrementAndGet();
            }
        }
    }

    /** Gateway thread: one snapshot → MarketDataSnapshotFullRefresh (W). Not stored: a resend would be stale. */
    private void onSnapshot(org.agrona.DirectBuffer buffer, int index) {
        MdSubscription sub = mdSubscriptions.get(SnapshotCodec.subscriptionId(buffer, index));
        if (sub == null || !sub.session().loggedOn) {
            return;
        }
        SnapshotCodec.decode(buffer, index, mdView);
        int decimals = sub.instrument().pricePrecision();
        FixEncoder w = begin("W", false).add(262, sub.mdReqId()).add(55, sub.instrument().symbol())
                .add(FixTags.TENOR, sub.instrument().tenor()).add(268, mdView.bidCount() + mdView.askCount());
        for (int i = 0; i < mdView.bidCount(); i++) {
            w.add(269, '0').addDecimal(270, mdView.bidPrice(i), decimals).add(271, mdView.bidQty(i));
        }
        for (int i = 0; i < mdView.askCount(); i++) {
            w.add(269, '1').addDecimal(270, mdView.askPrice(i), decimals).add(271, mdView.askQty(i));
        }
        registry.send(sub.session().logical);
    }

    /** Hands an input to the pipeline, or to the backlog if the inbound ring is full. Never blocks. */
    private void submit(int length) {
        if (backlog.isEmpty() && pipeline.submit(cmd, 0, length)) {
            return;
        }
        byte[] copy = new byte[length];
        cmd.getBytes(0, copy);
        backlog.add(copy);
        backlogSize = backlog.size();
    }

    /** True if nothing is waiting. */
    private boolean drainBacklog() {
        while (!backlog.isEmpty()) {
            byte[] next = backlog.peek();
            backlogView.wrap(next);
            if (!pipeline.submit(backlogView, 0, next.length)) {
                return false;
            }
            backlog.poll();
            backlogSize = backlog.size();
        }
        return true;
    }

    public int backlogSize() {
        return backlogSize;
    }

    private void rejectUnsent(FixSession s, String clOrdId, int ordRejReason, String text) {
        begin("8", true).add(37, "NONE").add(11, clOrdId).add(17, "X" + s.logical.config.senderCompId() + "-" + (++nextRejectId))
                .add(150, '8').add(39, '8').add(55, parser.str(55)).add(54, parser.has(54) ? parser.asChar(54) : '1')
                .add(151, 0).add(14, 0).add(6, 0).add(103, ordRejReason).add(58, text);
        registry.send(s.logical);
    }

    private void cancelRejectUnknown(FixSession s, String clOrdId, String origClOrdId, int responseTo) {
        begin("9", true).add(37, "NONE").add(11, clOrdId).add(41, origClOrdId).add(39, '8')
                .add(434, responseTo).add(102, 1).add(58, "Unknown order");
        registry.send(s.logical);
    }
}
