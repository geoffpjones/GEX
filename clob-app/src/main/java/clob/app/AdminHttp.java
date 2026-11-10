package clob.app;

import clob.common.Prices;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.MassCancelScope;
import clob.engine.SessionState;
import clob.refdata.FixingEvent;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.InputCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Operations HTTP API (JDK HttpServer, bound to localhost). Every admin action becomes a sequenced
 * engine input, so it is journalled, replicated and replayed like any order.
 *
 * <pre>
 * GET  /health                       liveness: 200 while working, 503 once failed (no token)
 * GET  /ready                        readiness: 200 only when serving and every consumer is keeping up (no token)
 * GET  /metrics                      Prometheus text (no token)
 * POST /admin/session?instrument=-1&amp;state=HALTED|OPEN|CLOSED|PRE_OPEN
 * POST /admin/killswitch             halt every instrument, then cancel every order
 * POST /admin/endofday
 * POST /admin/cancel?order=ID        admin cancel (bypasses min TTL and race holds)
 * POST /admin/credit/line?line=&amp;giver=&amp;type=UNLIMITED|DSL|NOP&amp;limit=USD
 * POST /admin/credit/relationship?from=&amp;to=&amp;line=[&amp;instrument=]   line=-1 for no credit
 * POST /admin/usdrate?ccy=EUR&amp;rate=1.0845
 * POST /admin/valuedates?instrument=&amp;near=2026-11-10[&amp;far=2026-12-10]
 * POST /admin/roll?today=2026-11-10
 * POST /admin/fixing/schedule?event=&amp;date=2026-11-10   (cut-off and fixing instants from the event's zone)
 * POST /admin/fixing/rate?event=&amp;instrument=&amp;rate=1.08123
 * POST /admin/snapshot               write a snapshot at the journal's end, in the background
 * POST /admin/promote                (standby only)
 * </pre>
 *
 * Admin calls need header {@code X-Admin-Token}. A 202 means submitted; refusals by the engine are
 * counted in {@code clob_admin_refused_total} and logged.
 */
final class AdminHttp implements AutoCloseable {
    @FunctionalInterface
    interface Submitter {
        boolean submit(DirectBuffer buffer, int offset, int length);
    }

    private final HttpServer server;
    private final NodeConfig cfg;
    private final RefData refData;
    private final Submitter submitter;
    private final Metrics metrics;

    AdminHttp(NodeConfig cfg, RefData refData, Submitter submitter, Metrics metrics, Supplier<String> health,
            java.util.function.BooleanSupplier live, java.util.function.BooleanSupplier ready, Runnable promote,
            Runnable snapshot) throws IOException {
        this.cfg = cfg;
        this.refData = refData;
        this.submitter = submitter;
        this.metrics = metrics;
        server = HttpServer.create(new InetSocketAddress(cfg.adminBindHost(), cfg.adminPort()), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "clob-admin-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/health", ex -> respond(ex, live.getAsBoolean() ? 200 : 503, health.get()));
        server.createContext("/ready", ex -> respond(ex, ready.getAsBoolean() ? 200 : 503, health.get()));
        server.createContext("/metrics", ex -> respond(ex, 200, metrics.render()));
        server.createContext("/admin/", ex -> {
            try {
                if (!"POST".equals(ex.getRequestMethod())) {
                    respond(ex, 405, "POST only");
                    return;
                }
                if (cfg.adminToken().isEmpty() || !cfg.adminToken().equals(ex.getRequestHeaders().getFirst("X-Admin-Token"))) {
                    respond(ex, 401, "bad or missing X-Admin-Token");
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (path.equals("/admin/promote")) {
                    if (promote == null) {
                        respond(ex, 409, "not a standby");
                        return;
                    }
                    respond(ex, 202, "promoting");
                    new Thread(promote, "clob-promote").start();
                    return;
                }
                if (path.equals("/admin/snapshot")) {
                    if (snapshot == null) {
                        respond(ex, 409, "primary only");
                        return;
                    }
                    snapshot.run();
                    respond(ex, 202, "snapshot started");
                    return;
                }
                if (submitter == null) {
                    respond(ex, 409, "standby: admin actions go to the primary");
                    return;
                }
                handle(path, query(ex));
                metrics.inc("clob_admin_requests_total", 1);
                respond(ex, 202, "submitted");
            } catch (IllegalStateException e) {
                respond(ex, 503, "not accepting: " + e.getMessage());
            } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException e) {
                respond(ex, 400, "bad request: " + e.getMessage());
            }
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(String path, Map<String, String> q) {
        UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        switch (path) {
            case "/admin/session" -> submit(b, InputCodec.sessionState(b, 0, intOr(q, "instrument", -1),
                    SessionState.valueOf(q.get("state"))));
            case "/admin/killswitch" -> {
                submit(b, InputCodec.sessionState(b, 0, -1, SessionState.HALTED));
                for (int a = 1; a <= cfg.maxAccounts(); a++) {
                    submit(b, InputCodec.massCancel(b, 0, MassCancelScope.ACCOUNT, a, 0, CancelSource.ADMIN));
                }
            }
            case "/admin/endofday" -> submit(b, InputCodec.endOfDay(b, 0));
            case "/admin/cancel" -> submit(b, InputCodec.cancel(b, 0, Long.parseLong(q.get("order")), 0, CancelSource.ADMIN));
            case "/admin/credit/line" -> submit(b, InputCodec.creditLine(b, 0, Integer.parseInt(q.get("line")),
                    Integer.parseInt(q.get("giver")), CreditType.valueOf(q.get("type")), Long.parseLong(q.getOrDefault("limit", "0"))));
            case "/admin/credit/relationship" -> submit(b, InputCodec.creditRelationship(b, 0, Integer.parseInt(q.get("from")),
                    Integer.parseInt(q.get("to")), intOr(q, "instrument", -1), Integer.parseInt(q.get("line"))));
            case "/admin/usdrate" -> submit(b, InputCodec.usdRate(b, 0, q.get("ccy"), Prices.toTicks(q.get("rate"), 8)));
            case "/admin/valuedates" -> {
                int instrument = Integer.parseInt(q.get("instrument"));
                if (refData.instrument(instrument) == null) {
                    throw new IllegalArgumentException("unknown instrument " + instrument);
                }
                submit(b, InputCodec.valueDates(b, 0, instrument, (int) LocalDate.parse(q.get("near")).toEpochDay(),
                        q.containsKey("far") ? (int) LocalDate.parse(q.get("far")).toEpochDay() : -1));
            }
            case "/admin/roll" -> submit(b, InputCodec.valueDateRoll(b, 0, (int) LocalDate.parse(q.get("today")).toEpochDay()));
            case "/admin/fixing/schedule" -> {
                FixingEvent event = refData.fixingEvent(Integer.parseInt(q.get("event")));
                if (event == null) {
                    throw new IllegalArgumentException("unknown fixing event");
                }
                FixingEvent.Occurrence occ = event.occurrenceOn(LocalDate.parse(q.get("date")));
                submit(b, InputCodec.fixingSchedule(b, 0, event.id(), occ.cutoffNanos(), occ.fixingNanos()));
            }
            case "/admin/fixing/rate" -> {
                Instrument instrument = refData.instrument(Integer.parseInt(q.get("instrument")));
                if (instrument == null) {
                    throw new IllegalArgumentException("unknown instrument");
                }
                submit(b, InputCodec.fixingRate(b, 0, Integer.parseInt(q.get("event")), instrument.id(),
                        Prices.toTicks(q.get("rate"), instrument.pricePrecision())));
            }
            default -> throw new IllegalArgumentException("unknown action " + path);
        }
    }

    private void submit(UnsafeBuffer b, int length) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!submitter.submit(b, 0, length)) {
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("the sequencer is not accepting input");
            }
            Thread.onSpinWait();
        }
    }

    private static int intOr(Map<String, String> q, String key, int dflt) {
        return q.containsKey(key) ? Integer.parseInt(q.get(key)) : dflt;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                String[] p = kv.split("=", 2);
                m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        return m;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
