package clob.fix;

import clob.common.Side;
import clob.engine.NewOrderCommand;
import clob.engine.RejectReason;
import clob.engine.ReplaceCommand;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import clob.engine.SessionState;
import clob.refdata.Instrument;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import clob.sequencer.InputCodec;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputReader;
import clob.sequencer.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Who owns each order and request, independently of any socket, and the outbound report stream.
 *
 * <p>Live, the gateway registers requests as it submits them and feeds engine output here. In recovery
 * or on a standby the same state is rebuilt by feeding every journalled input ({@link #onReplayedInput})
 * and the engine's replayed output ({@link #onReplayedOutput}). So order ids, ClOrdIDs, running
 * execution totals and pending fixing matches all come back with no separate persistence.
 *
 * <p>Modes: {@link Mode#SILENT} rebuilds state only (a standby). {@link Mode#CATCH_UP} also generates
 * reports for events past each session's stored high-water mark, which are what a crash left unsent.
 * {@link Mode#LIVE} reports everything. Owned by one thread at a time: recovery, then the gateway.
 */
public final class OrderRegistry implements AutoCloseable {
    public enum Mode { SILENT, CATCH_UP, LIVE }

    private record PendingRequest(LogicalSession session, GatewayOrder order, String clOrdId, RequestType type) {}

    private final FixGatewayConfig config;
    private final RefData refData;
    private final LongSupplier wallMillis;
    private final Map<String, LogicalSession> sessions = new HashMap<>();
    private final Long2ObjectHashMap<GatewayOrder> orders = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<PendingRequest> requests = new Long2ObjectHashMap<>();
    private final FixEncoder enc = new FixEncoder();
    private final OutputReader event = new OutputReader();
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private final long idBase;
    private long lastOrderId;
    private long lastRequestId;
    /** Count of engine output events applied; deterministic across replay. */
    private long ordinal;
    private Mode mode;
    private boolean pendingStore;
    private String pendingType;
    private int pendingMark;

    public OrderRegistry(FixGatewayConfig config, RefData refData, LongSupplier wallMillis, Mode mode) {
        this.config = config;
        this.refData = refData;
        this.wallMillis = wallMillis;
        this.mode = mode;
        this.idBase = (long) config.gatewayId() << 40;
        this.lastOrderId = idBase;
        this.lastRequestId = idBase;
        for (FixSessionConfig s : config.sessions()) {
            sessions.put(s.senderCompId(), new LogicalSession(s, new FixStore(config.storeDir(), s.senderCompId())));
        }
    }

    /** Recovery is complete; from here every event is reported. */
    public void goLive() {
        mode = Mode.LIVE;
    }

    LogicalSession session(String senderCompId) {
        return sessions.get(senderCompId);
    }

    long nextOrderId() {
        return ++lastOrderId;
    }

    void releaseOrderId() {
        lastOrderId--;
    }

    long nextRequestId() {
        return ++lastRequestId;
    }

    GatewayOrder order(long orderId) {
        return orders.get(orderId);
    }

    public int liveOrderCount() {
        return orders.size();
    }

    /** Highest order id used so far (restored from history). */
    public long lastOrderId() {
        return lastOrderId;
    }

    GatewayOrder registerOrder(long orderId, LogicalSession session, String clOrdId, Instrument instrument, int account,
            char side, char ordType, char tif, long qty, long price) {
        GatewayOrder g = new GatewayOrder(orderId, session, clOrdId, instrument, account, side, ordType, tif, qty, price);
        orders.put(orderId, g);
        session.clOrdIds.put(clOrdId, orderId);
        return g;
    }

    void registerRequest(long requestId, LogicalSession session, GatewayOrder order, String clOrdId, RequestType type) {
        requests.put(requestId, new PendingRequest(session, order, clOrdId, type));
        session.clOrdIds.put(clOrdId, order != null ? order.orderId : 0L);
    }

    // ------------------------------------------------------------------ snapshots

    /** Writes everything rebuilt from history. Sequence numbers and resend stores have their own files. */
    public void save(java.io.DataOutput out) throws IOException {
        out.writeLong(lastOrderId);
        out.writeLong(lastRequestId);
        out.writeLong(ordinal);
        out.writeInt(sessions.size());
        for (LogicalSession s : sessions.values()) {
            out.writeUTF(s.config.senderCompId());
            out.writeInt(s.clOrdIds.size());
            for (Map.Entry<String, Long> e : s.clOrdIds.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeLong(e.getValue());
            }
        }
        out.writeInt(orders.size());
        for (GatewayOrder g : orders.values()) {
            out.writeLong(g.orderId);
            out.writeUTF(g.session.config.senderCompId());
            out.writeInt(g.instrument.id());
            out.writeInt(g.account);
            out.writeChar(g.side);
            out.writeChar(g.ordType);
            out.writeChar(g.tif);
            out.writeUTF(g.clOrdId);
            out.writeLong(g.qty);
            out.writeLong(g.price);
            out.writeLong(g.leaves);
            out.writeLong(g.cumQty);
            out.writeDouble(g.notional);
            out.writeLong(g.pendingFixing);
            out.writeBoolean(g.terminal);
        }
        out.writeInt(requests.size());
        for (Long2ObjectHashMap<PendingRequest>.KeyIterator it = requests.keySet().iterator(); it.hasNext(); ) {
            long id = it.nextLong();
            PendingRequest r = requests.get(id);
            out.writeLong(id);
            out.writeUTF(r.session().config.senderCompId());
            out.writeLong(r.order() != null ? r.order().orderId : 0);
            out.writeUTF(r.clOrdId());
            out.writeUTF(r.type().name());
        }
    }

    /** Loads a saved state into this new registry. Sessions no longer configured are dropped. */
    public void restore(java.io.DataInput in) throws IOException {
        lastOrderId = in.readLong();
        lastRequestId = in.readLong();
        ordinal = in.readLong();
        int sessionCount = in.readInt();
        for (int i = 0; i < sessionCount; i++) {
            LogicalSession s = sessions.get(in.readUTF());
            int ids = in.readInt();
            for (int k = 0; k < ids; k++) {
                String clOrdId = in.readUTF();
                long orderId = in.readLong();
                if (s != null) {
                    s.clOrdIds.put(clOrdId, orderId);
                }
            }
        }
        int orderCount = in.readInt();
        for (int i = 0; i < orderCount; i++) {
            long orderId = in.readLong();
            LogicalSession s = sessions.get(in.readUTF());
            Instrument instrument = refData.instrument(in.readInt());
            int account = in.readInt();
            char side = in.readChar();
            char ordType = in.readChar();
            char tif = in.readChar();
            String clOrdId = in.readUTF();
            long qty = in.readLong();
            long price = in.readLong();
            long leaves = in.readLong();
            long cumQty = in.readLong();
            double notional = in.readDouble();
            long pendingFixing = in.readLong();
            boolean terminal = in.readBoolean();
            if (s != null) {
                GatewayOrder g = new GatewayOrder(orderId, s, clOrdId, instrument, account, side, ordType, tif, qty, price);
                g.leaves = leaves;
                g.cumQty = cumQty;
                g.notional = notional;
                g.pendingFixing = pendingFixing;
                g.terminal = terminal;
                orders.put(orderId, g);
            }
        }
        int requestCount = in.readInt();
        for (int i = 0; i < requestCount; i++) {
            long id = in.readLong();
            LogicalSession s = sessions.get(in.readUTF());
            long orderId = in.readLong();
            String clOrdId = in.readUTF();
            RequestType type = RequestType.valueOf(in.readUTF());
            if (s != null) {
                requests.put(id, new PendingRequest(s, orders.get(orderId), clOrdId, type));
            }
        }
    }

    // ------------------------------------------------------------------ rebuilding from history

    /** A journalled input, before the engine applies it. Inputs without FIX identity are ignored. */
    public void onReplayedInput(DirectBuffer b, int o) {
        int at = InputCodec.identityOffset(b, o);
        if (at < 0) {
            return;
        }
        LogicalSession session = sessions.get(InputCodec.identityCompId(b, at));
        String clOrdId = InputCodec.identityClOrdId(b, at);
        switch (InputCodec.type(b, o)) {
            case InputCodec.NEW_ORDER -> {
                InputCodec.decodeNewOrder(b, o, newOrder.reset());
                lastOrderId = Math.max(lastOrderId, newOrder.orderId);
                Instrument instrument = refData.instrument(newOrder.instrumentId);
                if (session != null && instrument != null) {
                    registerOrder(newOrder.orderId, session, clOrdId, instrument, newOrder.accountId,
                            newOrder.side == Side.BUY ? '1' : '2', (char) InputCodec.identityOrdType(b, at),
                            tifChar(newOrder.tif), newOrder.qty, newOrder.type == OrderType.MARKET ? 0 : newOrder.price);
                }
            }
            case InputCodec.CANCEL -> {
                long requestId = InputCodec.cancelRequestId(b, o);
                lastRequestId = Math.max(lastRequestId, requestId);
                if (session != null) {
                    registerRequest(requestId, session, orders.get(InputCodec.cancelOrderId(b, o)), clOrdId, RequestType.CANCEL);
                }
            }
            case InputCodec.REPLACE -> {
                InputCodec.decodeReplace(b, o, replace.reset());
                lastRequestId = Math.max(lastRequestId, replace.requestId);
                if (session != null) {
                    registerRequest(replace.requestId, session, orders.get(replace.orderId), clOrdId, RequestType.REPLACE);
                }
            }
            default -> { }
        }
    }

    private static char tifChar(TimeInForce tif) {
        return switch (tif) {
            case DAY -> '0';
            case GTC -> '1';
            case IOC -> '3';
            case FOK -> '4';
            case GTT -> '6';
        };
    }

    /** One engine output event produced while replaying history. */
    public void onReplayedOutput(DirectBuffer buffer, int offset) {
        onOutput(buffer, offset);
    }

    // ------------------------------------------------------------------ outbound messages

    /** Starts a message body. Application messages ({@code store}) are kept for ResendRequest. */
    FixEncoder begin(String msgType, boolean store) {
        pendingStore = store;
        pendingType = msgType;
        enc.begin(msgType, "", "", 0, 0, false, 0);
        pendingMark = enc.mark();
        return enc;
    }

    /** Assigns the next sequence number to the message begun with {@link #begin}; delivers it if connected. */
    void send(LogicalSession s) {
        byte[] body = enc.bodySince(pendingMark);
        long now = wallMillis.getAsLong();
        long seq = s.store.takeNextOut();
        enc.begin(pendingType, config.compId(), s.config.senderCompId(), seq, now, false, 0);
        enc.addRaw(body);
        int length = enc.finish();
        if (s.connection != null) {
            s.connection.queue(enc.bytes(), length);
            s.connection.lastOutMillis = now;
        }
        if (pendingStore) {
            s.store.record(seq, pendingType, body, now, ordinal);
        }
    }

    FixEncoder encoder() {
        return enc;
    }

    /** Writes every session's buffered store records. Call before the bytes reach the sockets. */
    void flushStores() {
        for (LogicalSession s : sessions.values()) {
            s.store.flush();
        }
    }

    private boolean reports(LogicalSession s) {
        // With no stored high-water mark the store was lost or never used: replaying all history at the client would be wrong.
        return mode == Mode.LIVE || mode == Mode.CATCH_UP && s.store.highestOrdinal() > 0 && ordinal > s.store.highestOrdinal();
    }

    // ------------------------------------------------------------------ engine output

    /** Applies one engine output event: updates ownership and totals, and reports it to the owner. */
    void onOutput(DirectBuffer buffer, int offset) {
        event.read(buffer, offset);
        ordinal++;
        switch (event.type) {
            case OutputEncoder.ACCEPTED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.leaves = g.qty;
                    if (reports(g.session)) {
                        exec(g, '0', g.clOrdId, null).finish(g);
                    }
                }
            }
            case OutputEncoder.REJECTED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.terminal = true;
                    if (reports(g.session)) {
                        RejectReason r = Wire.REJECT_REASONS.of(event.reason);
                        exec(g, '8', g.clOrdId, null).status('8')
                                .add(103, r == RejectReason.UNKNOWN_INSTRUMENT ? 1 : r == RejectReason.DUPLICATE_ORDER_ID ? 6 : 99)
                                .add(58, r.name()).finish(g);
                    }
                    forget(g);
                }
            }
            case OutputEncoder.TRADE -> {
                onTradeSide(event.aggressorOrderId, event.aggressorLeaves, 'A');
                onTradeSide(event.passiveOrderId, event.passiveLeaves, 'P');
            }
            case OutputEncoder.FIXING_MATCHED -> {
                onFixingMatchSide(event.aggressorOrderId, event.aggressorLeaves, 'A');
                onFixingMatchSide(event.passiveOrderId, event.passiveLeaves, 'P');
            }
            case OutputEncoder.CANCELLED -> onCancelled();
            case OutputEncoder.REPLACED -> onReplaced();
            case OutputEncoder.REQUEST_REJECTED -> onRequestRejected();
            case OutputEncoder.REQUEST_QUEUED -> onRequestQueued();
            case OutputEncoder.REPRICED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null) {
                    g.price = event.price;
                    if (reports(g.session)) {
                        exec(g, 'D', g.clOrdId, null).add(378, 3).finish(g);
                    }
                }
            }
            case OutputEncoder.SUSPENDED -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null && reports(g.session)) {
                    exec(g, 'D', g.clOrdId, null).add(378, 99).add(58, "SUSPENDED").finish(g);
                }
            }
            case OutputEncoder.JOINED_RACE -> {
                GatewayOrder g = orders.get(event.orderId);
                if (g != null && reports(g.session)) {
                    exec(g, 'I', g.clOrdId, null).add(FixTags.RACE_ID, event.raceId).add(58, "RACE").finish(g);
                }
            }
            case OutputEncoder.SESSION_STATE -> onSessionState();
            case OutputEncoder.END_OF_DAY -> onEndOfDay();
            default -> { } // RACE_STARTED and credit alerts are not reported to clients
        }
    }

    private void onCancelled() {
        GatewayOrder g = orders.get(event.orderId);
        if (g == null) {
            return;
        }
        g.leaves = 0;
        g.terminal = true;
        PendingRequest request = event.requestId != 0 ? requests.remove(event.requestId) : null;
        if (reports(g.session)) {
            String clOrdId = request != null ? request.clOrdId() : g.clOrdId;
            exec(g, '4', clOrdId, request != null ? g.clOrdId : null).status('4')
                    .add(58, Wire.CANCEL_REASONS.of(event.reason).name()).finish(g);
        }
        forget(g);
    }

    private void onReplaced() {
        GatewayOrder g = orders.get(event.orderId);
        if (g == null) {
            return;
        }
        String old = g.clOrdId;
        g.qty = event.qty;
        g.price = event.price;
        g.leaves = event.leaves;
        PendingRequest request = event.requestId != 0 ? requests.remove(event.requestId) : null;
        if (request != null) {
            g.clOrdId = request.clOrdId();
        }
        if (reports(g.session)) {
            exec(g, '5', g.clOrdId, old).finish(g);
        }
    }

    private void onRequestRejected() {
        PendingRequest request = requests.remove(event.requestId);
        if (request == null || !reports(request.session())) {
            return;
        }
        RequestType type = Wire.REQUEST_TYPES.of(event.requestType);
        RequestRejectReason r = Wire.REQUEST_REJECT_REASONS.of(event.reason);
        GatewayOrder g = request.order();
        begin("9", true).add(37, g != null ? String.valueOf(g.orderId) : "NONE").add(11, request.clOrdId())
                .add(41, g != null ? g.clOrdId : null).add(39, g != null ? g.ordStatus() : '8')
                .add(434, type == RequestType.CANCEL ? 1 : 2)
                .add(102, r == RequestRejectReason.UNKNOWN_ORDER ? 1 : r == RequestRejectReason.TOO_LATE ? 0 : 99)
                .add(58, r.name());
        send(request.session());
    }

    private void onRequestQueued() {
        PendingRequest request = requests.get(event.requestId);
        if (request != null && request.order() != null && reports(request.session())) {
            GatewayOrder g = request.order();
            char pending = request.type() == RequestType.CANCEL ? '6' : 'E';
            exec(g, pending, request.clOrdId(), g.clOrdId).status(pending).finish(g);
        }
    }

    private void onSessionState() {
        if (mode != Mode.LIVE) {
            return; // not stored: only connected sessions are told about state changes
        }
        Instrument instrument = refData.instrument(event.instrumentId);
        SessionState state = Wire.SESSION_STATES.of(event.sessionState);
        int status = switch (state) {
            case OPEN -> 2;
            case HALTED -> 1;
            case CLOSED -> 3;
            case PRE_OPEN -> 4;
        };
        for (LogicalSession s : sessions.values()) {
            if (s.connected()) {
                begin("h", false).add(336, "CLOB").add(340, status).add(FixTags.SYMBOL, instrument.symbol())
                        .add(FixTags.TENOR, instrument.tenor());
                send(s);
            }
        }
    }

    /**
     * End of day: ClOrdIDs only need to stay unique for the session, so everything except live orders'
     * current ids is dropped. This bounds the map by live orders plus one day's requests.
     */
    private void onEndOfDay() {
        for (LogicalSession s : sessions.values()) {
            s.clOrdIds.clear();
        }
        for (GatewayOrder g : orders.values()) {
            g.session.clOrdIds.put(g.clOrdId, g.orderId);
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
        if (reports(g.session)) {
            exec(g, 'F', g.clOrdId, null, role).add(32, event.qty).addDecimal(31, event.price, event.priceDecimals)
                    .add(FixTags.TRADE_ID, "T" + event.tradeId + role).finish(g);
        }
        if (g.leaves == 0 && g.pendingFixing == 0) {
            g.terminal = true;
            forget(g);
        }
    }

    private void onFixingMatchSide(long orderId, long leaves, char role) {
        GatewayOrder g = orders.get(orderId);
        if (g == null) {
            return;
        }
        g.leaves = leaves;
        g.pendingFixing += event.qty;
        if (reports(g.session)) {
            exec(g, 'I', g.clOrdId, null, role).add(FixTags.FIXING_MATCHED_QTY, event.qty).add(58, "FIXING MATCHED").finish(g);
        }
    }

    private void forget(GatewayOrder g) {
        if (g.pendingFixing == 0) {
            orders.remove(g.orderId);
        }
    }

    /** Sends the current state of the session's live orders (OrderStatusRequest with ClOrdID "*"). */
    void reportStatus(LogicalSession s, GatewayOrder only) {
        List<GatewayOrder> list = new ArrayList<>();
        if (only != null) {
            list.add(only);
        } else {
            for (GatewayOrder g : orders.values()) {
                if (g.session == s) {
                    list.add(g);
                }
            }
            list.sort(java.util.Comparator.comparingLong(g -> g.orderId));
        }
        for (GatewayOrder g : list) {
            exec(g, 'I', g.clOrdId, null, 'S').add(58, "STATUS").finish(g);
        }
    }

    private ExecBuilder exec(GatewayOrder g, char execType, String clOrdId, String origClOrdId) {
        return exec(g, execType, clOrdId, origClOrdId, 'X');
    }

    /** ExecID derives from the engine event, so it is the same if the report is regenerated after a crash. */
    private ExecBuilder exec(GatewayOrder g, char execType, String clOrdId, String origClOrdId, char side) {
        execBuilder.encoder = begin("8", true).add(37, g.orderId).add(11, clOrdId).add(41, origClOrdId)
                .add(17, "E" + ordinal + side).add(150, execType);
        execBuilder.status = 0;
        return execBuilder;
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
            send(g.session);
        }
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }


    @Override
    public void close() throws IOException {
        for (LogicalSession s : sessions.values()) {
            s.store.close();
        }
    }
}
