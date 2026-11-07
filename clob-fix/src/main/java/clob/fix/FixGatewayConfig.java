package clob.fix;

import java.util.List;

/** Gateway settings. {@code port} 0 picks a free port (tests). {@code gatewayId} namespaces order ids. */
public record FixGatewayConfig(String compId, int port, int gatewayId, List<FixSessionConfig> sessions,
        int logonTimeoutMillis) {}
