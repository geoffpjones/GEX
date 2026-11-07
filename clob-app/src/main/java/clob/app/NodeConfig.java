package clob.app;

import clob.fix.FixSessionConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Node settings (a properties file). See {@code config/node.properties} for an annotated example.
 * Ports may be 0 (pick a free port; tests).
 */
public record NodeConfig(Path refDataFile, Path journalDir, Path dataDir, int maxAccounts, long randomSeed,
        String fixCompId, int fixPort, List<FixSessionConfig> fixSessions, int adminPort, String adminToken,
        long mdIntervalNanos, int replicationPort, String primaryHost, int primaryReplicationPort) {

    public static NodeConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        }
        Path base = file.toAbsolutePath().getParent();
        return from(p, base);
    }

    public static NodeConfig from(Properties p, Path base) {
        List<FixSessionConfig> sessions = new ArrayList<>();
        // fix.session.N = senderCompId,username,password,userId,cancelOnDisconnect,maxMsgsPerSec
        for (String key : p.stringPropertyNames().stream().filter(k -> k.startsWith("fix.session.")).sorted().toList()) {
            String[] f = p.getProperty(key).split(",");
            sessions.add(new FixSessionConfig(f[0].trim(), f[1].trim(), f[2].trim(), Integer.parseInt(f[3].trim()),
                    Boolean.parseBoolean(f[4].trim()), Integer.parseInt(f[5].trim())));
        }
        return new NodeConfig(
                base.resolve(p.getProperty("refdata.file", "refdata.txt")),
                base.resolve(p.getProperty("journal.dir", "journal")),
                base.resolve(p.getProperty("data.dir", "data")),
                Integer.parseInt(p.getProperty("accounts.max", "256")),
                Long.parseLong(p.getProperty("engine.randomSeed", "1")),
                p.getProperty("fix.compId", "CLOB"),
                Integer.parseInt(p.getProperty("fix.port", "9876")),
                sessions,
                Integer.parseInt(p.getProperty("admin.port", "8080")),
                p.getProperty("admin.token", ""),
                Long.parseLong(p.getProperty("md.intervalMs", "5")) * 1_000_000L,
                Integer.parseInt(p.getProperty("replication.port", "-1")),
                p.getProperty("replication.primaryHost", "127.0.0.1"),
                Integer.parseInt(p.getProperty("replication.primaryPort", "-1")));
    }
}
