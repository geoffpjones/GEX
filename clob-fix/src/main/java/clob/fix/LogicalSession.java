package clob.fix;

import java.util.HashMap;
import java.util.Map;

/**
 * A configured FIX session, independent of any TCP connection: sequence numbers, the resend store and
 * the ClOrdIDs it has used. It outlives disconnects and, through the store and journal, restarts.
 */
final class LogicalSession {
    final FixSessionConfig config;
    final FixStore store;
    /** ClOrdID of every application request this session → engine order id. Unique across all of them. */
    final Map<String, Long> clOrdIds = new HashMap<>();
    /** The connection currently logged on, or null. */
    FixSession connection;

    LogicalSession(FixSessionConfig config, FixStore store) {
        this.config = config;
        this.store = store;
    }

    long expectedIn() {
        return store.expectedIn();
    }

    void expectedIn(long value) {
        store.expectedIn(value);
    }

    boolean connected() {
        return connection != null && connection.loggedOn;
    }
}
