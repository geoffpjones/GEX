package clob.fix;

import java.nio.file.Path;
import java.util.List;

/**
 * Gateway settings. {@code port} 0 picks a free port (tests). {@code gatewayId} namespaces order ids.
 * {@code bindHost} is the listen address. {@code storeDir} holds durable session state; null keeps it in memory.
 */
public record FixGatewayConfig(String compId, String bindHost, int port, int gatewayId, List<FixSessionConfig> sessions,
        int logonTimeoutMillis, Path storeDir) {

    public FixGatewayConfig(String compId, int port, int gatewayId, List<FixSessionConfig> sessions, int logonTimeoutMillis) {
        this(compId, "127.0.0.1", port, gatewayId, sessions, logonTimeoutMillis, null);
    }
}
