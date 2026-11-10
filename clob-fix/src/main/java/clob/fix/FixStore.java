package clob.fix;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Outbound message store and sequence numbers of one logical FIX session. The most recent
 * {@value #RETAINED} messages are kept in memory for ResendRequest; older ones are gap-filled. With a
 * directory it also survives a restart: messages are appended to {@code <id>.store} and the sequence
 * numbers live in a small mapped file. Without one (tests, a promoted standby) it is memory only.
 *
 * <p>Each stored report carries the ordinal of the engine event that produced it. After a restart,
 * events past the highest stored ordinal have not been reported, so recovery generates just those.
 */
final class FixStore implements AutoCloseable {
    static final int RETAINED = 1 << 17;
    private static final long COMPACT_BYTES = 1L << 30;
    private static final int RECORD_HEADER = 4 + 8 + 8 + 8 + 2;

    record Entry(long seq, String msgType, byte[] body, long millis, long ordinal) {}

    private final Entry[] ring = new Entry[RETAINED];
    private final Path file;
    private FileChannel channel;
    private final ByteBuffer pending = ByteBuffer.allocate(1 << 18).order(ByteOrder.LITTLE_ENDIAN);
    private final MappedByteBuffer seqs;
    private long fileBytes;
    private long highestOrdinal;
    private long nextOut = 1;
    private long expectedIn = 1;

    FixStore(Path dir, String senderCompId) {
        if (dir == null) {
            file = null;
            seqs = null;
            return;
        }
        try {
            Files.createDirectories(dir);
            String id = senderCompId.replaceAll("[^A-Za-z0-9_-]", "_");
            file = dir.resolve(id + ".store");
            try (RandomAccessFile raf = new RandomAccessFile(dir.resolve(id + ".seqs").toFile(), "rw");
                    FileChannel seqChannel = raf.getChannel()) {
                boolean fresh = seqChannel.size() == 0;
                seqs = seqChannel.map(FileChannel.MapMode.READ_WRITE, 0, 16);
                seqs.order(ByteOrder.LITTLE_ENDIAN);
                if (fresh) {
                    seqs.putLong(0, 1).putLong(8, 1);
                }
                nextOut = seqs.getLong(0);
                expectedIn = seqs.getLong(8);
            }
            load();
            channel = FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            channel.truncate(fileBytes); // drop any torn tail
            channel.position(fileBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        byte[] all = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= RECORD_HEADER) {
            int start = b.position();
            int bodyLength = b.getInt();
            if (bodyLength < 0 || bodyLength > b.remaining()) {
                b.position(start);
                break;
            }
            long seq = b.getLong();
            long millis = b.getLong();
            long ordinal = b.getLong();
            int typeLength = b.getShort();
            if (typeLength < 0 || b.remaining() < typeLength + bodyLength) {
                b.position(start);
                break;
            }
            byte[] type = new byte[typeLength];
            b.get(type);
            byte[] body = new byte[bodyLength];
            b.get(body);
            ring[slot(seq)] = new Entry(seq, new String(type, StandardCharsets.US_ASCII), body, millis, ordinal);
            highestOrdinal = Math.max(highestOrdinal, ordinal);
        }
        fileBytes = b.position();
    }

    private static int slot(long seq) {
        return (int) (seq & (RETAINED - 1));
    }

    long nextOut() {
        return nextOut;
    }

    long expectedIn() {
        return expectedIn;
    }

    void expectedIn(long value) {
        expectedIn = value;
        if (seqs != null) {
            seqs.putLong(8, value);
        }
    }

    long takeNextOut() {
        long seq = nextOut++;
        if (seqs != null) {
            seqs.putLong(0, nextOut);
        }
        return seq;
    }

    /** Highest engine event already reported, as recorded at load. */
    long highestOrdinal() {
        return highestOrdinal;
    }

    /** Records an application message for resend. */
    void record(long seq, String msgType, byte[] body, long millis, long ordinal) {
        ring[slot(seq)] = new Entry(seq, msgType, body, millis, ordinal);
        if (ordinal > highestOrdinal) {
            highestOrdinal = ordinal;
        }
        if (file != null) {
            byte[] type = msgType.getBytes(StandardCharsets.US_ASCII);
            int size = RECORD_HEADER + type.length + body.length;
            if (pending.remaining() < size) {
                flush();
            }
            pending.putInt(body.length).putLong(seq).putLong(millis).putLong(ordinal).putShort((short) type.length);
            pending.put(type).put(body);
        }
    }

    /** The stored message with this sequence number, or null if it was an admin message or has aged out. */
    Entry get(long seq) {
        Entry e = ring[slot(seq)];
        return e != null && e.seq() == seq ? e : null;
    }

    /** Writes buffered records. Called before the matching bytes reach the socket. */
    void flush() {
        if (file == null || pending.position() == 0) {
            return;
        }
        pending.flip();
        try {
            while (pending.hasRemaining()) {
                fileBytes += channel.write(pending);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            pending.clear();
        }
        if (fileBytes > COMPACT_BYTES) {
            compact();
        }
    }

    /** Rewrites the file with only the retained messages, so a session that never resets stays bounded. */
    private void compact() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel out = FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            long bytes = 0;
            for (long seq = Math.max(1, nextOut - RETAINED); seq < nextOut; seq++) {
                Entry e = get(seq);
                if (e == null) {
                    continue;
                }
                byte[] type = e.msgType().getBytes(StandardCharsets.US_ASCII);
                ByteBuffer b = ByteBuffer.allocate(RECORD_HEADER + type.length + e.body().length).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(e.body().length).putLong(e.seq()).putLong(e.millis()).putLong(e.ordinal()).putShort((short) type.length);
                b.put(type).put(e.body()).flip();
                while (b.hasRemaining()) {
                    bytes += out.write(b);
                }
            }
            channel.close();
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            channel = FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE);
            channel.position(bytes);
            fileBytes = bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ResetSeqNumFlag: forget everything sent. Keeps the ordinal, so reports are not regenerated. */
    void reset() {
        java.util.Arrays.fill(ring, null);
        nextOut = 1;
        if (seqs != null) {
            seqs.putLong(0, 1);
        }
        expectedIn(1);
        pending.clear();
        if (file != null) {
            try {
                channel.truncate(0);
                channel.position(0);
                fileBytes = 0;
                if (highestOrdinal > 0) {
                    // Keep the high-water mark durable with a zero-length marker record.
                    record(0, "", new byte[0], 0, highestOrdinal);
                    ring[0] = null;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        if (channel != null) {
            channel.close();
        }
    }
}
