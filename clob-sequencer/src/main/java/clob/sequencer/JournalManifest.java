package clob.sequencer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Everything replay depends on besides the journalled inputs: the message schema, a digest of the
 * reference data, the lottery seed and the capacities that shape engine state. It is written when a
 * journal is created and checked before any replay or replication, so a changed tick rule,
 * entitlement or seed fails startup rather than silently changing history.
 */
public record JournalManifest(int schemaVersion, String refDataDigest, long randomSeed, int maxAccounts,
        String engineCapacity) {

    public static final String FILE = "MANIFEST";

    /** Thrown when existing history was produced under a different contract. */
    public static final class MismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        MismatchException(String message) {
            super(message);
        }
    }

    /** Digest of reference data text, ignoring comments, blank lines and spacing. */
    public static String digest(List<String> refDataLines) {
        String canonical = refDataLines.stream()
                .map(l -> l.contains("#") ? l.substring(0, l.indexOf('#')) : l)
                .map(l -> l.trim().replaceAll("\\s+", " "))
                .filter(l -> !l.isEmpty())
                .collect(Collectors.joining("\n"));
        return sha256(canonical);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One digest of the whole manifest, compared during the replication handshake. */
    public String fingerprint() {
        return sha256(render());
    }

    private String render() {
        Map<String, String> m = new TreeMap<>();
        m.put("schemaVersion", String.valueOf(schemaVersion));
        m.put("refDataDigest", refDataDigest);
        m.put("randomSeed", String.valueOf(randomSeed));
        m.put("maxAccounts", String.valueOf(maxAccounts));
        m.put("engineCapacity", engineCapacity);
        return m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n")) + "\n";
    }

    private static JournalManifest parse(List<String> lines) {
        Map<String, String> m = new TreeMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        try {
            return new JournalManifest(Integer.parseInt(m.get("schemaVersion")), m.get("refDataDigest"),
                    Long.parseLong(m.get("randomSeed")), Integer.parseInt(m.get("maxAccounts")), m.get("engineCapacity"));
        } catch (NumberFormatException e) {
            throw new MismatchException("Unreadable journal manifest: " + e.getMessage());
        }
    }

    /**
     * Writes this manifest to a new journal, or checks it against the one already there. Fails if the
     * contract differs, or if a journal has records but no manifest.
     */
    public void verifyOrCreate(Path journalDir) {
        Path file = journalDir.resolve(FILE);
        try {
            Files.createDirectories(journalDir);
            if (Files.exists(file)) {
                JournalManifest existing = parse(Files.readAllLines(file));
                if (existing.schemaVersion != schemaVersion) {
                    throw new MismatchException("Journal schema " + existing.schemaVersion + " is not supported by this "
                            + "build (schema " + schemaVersion + ")");
                }
                if (!existing.refDataDigest.equals(refDataDigest)) {
                    throw new MismatchException("Reference data differs from the journal's");
                }
                if (existing.randomSeed != randomSeed) {
                    throw new MismatchException("Random seed differs from the journal's");
                }
                if (existing.maxAccounts != maxAccounts) {
                    throw new MismatchException("accounts.max differs from the journal's");
                }
                if (!existing.engineCapacity.equals(engineCapacity)) {
                    throw new MismatchException("Engine capacity differs from the journal's");
                }
                return;
            }
            try (var entries = Files.list(journalDir)) {
                if (entries.anyMatch(p -> p.getFileName().toString().startsWith("journal-"))) {
                    throw new MismatchException("Journal in " + journalDir + " has no manifest");
                }
            }
            Path tmp = journalDir.resolve(FILE + ".tmp");
            Files.writeString(tmp, render());
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
