package clob.app;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Snapshot files, {@code snapshot-<sequence>.bin}: a checksummed, atomically written image of engine and
 * order-ownership state as of one journal sequence. Recovery loads the newest valid one and replays the
 * journal from there. A damaged or incompatible snapshot is skipped, falling back to an older one or to
 * the journal alone, so a snapshot can never be the only copy of anything.
 */
final class SnapshotStore {
    private static final long MAGIC = 0x314E5350424F4C43L;
    private static final int HEADER = 8 + 64 + 8 + 8 + 4 + 4;
    private static final int RETAINED = 3;

    record Snapshot(long sequence, long time, byte[] payload) {}

    @FunctionalInterface
    interface Payload {
        void write(DataOutputStream out) throws IOException;
    }

    private SnapshotStore() {}

    static void write(Path dir, String fingerprint, long sequence, long time, Payload payload) throws IOException {
        Files.createDirectories(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            payload.write(out);
        }
        byte[] body = bytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(body);
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        header.putLong(MAGIC).put(fingerprint.getBytes(StandardCharsets.US_ASCII)).putLong(sequence).putLong(time)
                .putInt(body.length).putInt((int) crc.getValue()).flip();
        Path tmp = dir.resolve("snapshot.tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(header);
            ByteBuffer b = ByteBuffer.wrap(body);
            while (b.hasRemaining()) {
                ch.write(b);
            }
            ch.force(true);
        }
        Files.move(tmp, dir.resolve(String.format("snapshot-%020d.bin", sequence)), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        List<Path> all = list(dir);
        for (int i = RETAINED; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }

    /** Newest first. */
    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            List<Path> all = new ArrayList<>(s.filter(p -> p.getFileName().toString().matches("snapshot-\\d{20}\\.bin")).toList());
            all.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            return all;
        }
    }

    /** The newest intact snapshot written under this manifest, or null. */
    static Snapshot latest(Path dir, String fingerprint) throws IOException {
        for (Path file : list(dir)) {
            byte[] all = Files.readAllBytes(file);
            if (all.length < HEADER) {
                System.err.println("Skipping truncated snapshot " + file);
                continue;
            }
            ByteBuffer b = ByteBuffer.wrap(all);
            long magic = b.getLong();
            byte[] fp = new byte[64];
            b.get(fp);
            long sequence = b.getLong();
            long time = b.getLong();
            int length = b.getInt();
            int expected = b.getInt();
            if (magic != MAGIC || !fingerprint.equals(new String(fp, StandardCharsets.US_ASCII)) || length != all.length - HEADER) {
                System.err.println("Skipping incompatible snapshot " + file);
                continue;
            }
            CRC32C crc = new CRC32C();
            crc.update(all, HEADER, length);
            if ((int) crc.getValue() != expected) {
                System.err.println("Skipping corrupt snapshot " + file);
                continue;
            }
            byte[] payload = new byte[length];
            System.arraycopy(all, HEADER, payload, 0, length);
            return new Snapshot(sequence, time, payload);
        }
        return null;
    }
}
