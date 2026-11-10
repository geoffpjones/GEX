package clob.sequencer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Write-ahead journal of stamped inputs, on memory-mapped segment files ({@code journal-NNNNNN.seg}).
 * Segments are pre-touched when created, so appends never page-fault on the hot path. A stand-in for
 * Aeron Archive with the same role: the sequencer appends before the engine sees an input, and recovery
 * replays it.
 *
 * <pre>
 * segment: 64-byte header (magic "CLOBJNL1", int version, int segment index), then records:
 * record:  int payloadLength, int crc32c(payload), payload, padding to 8 bytes
 *          payloadLength 0 = end of written data; -1 = segment full, continue in the next segment
 * </pre>
 *
 * On open and replay, every segment's identity and every record's CRC and sequence continuity are
 * checked. Only a bad final record with nothing written after it is a torn write from a crash: it
 * is dropped and appending resumes there. Anything else is corruption of committed history and fails
 * the open, because later records exist and must not be silently discarded. Recover from a replica or
 * backup.
 *
 * Durability: appends land in the page cache and survive a process crash. {@link #flush()} forces
 * them to disk and can be called from another thread; the node runs it on an interval, which bounds
 * what a power loss can lose. A segment left behind at rollover is flushed by the next {@link #flush()}.
 */
public final class Journal implements AutoCloseable {
    static final int SEGMENT_HEADER = 64;
    static final long MAGIC = 0x314C4E4A424F4C43L; // "CLOBJNL1" little-endian
    static final int VERSION = Wire.SCHEMA_VERSION;
    static final int RECORD_HEADER = 8;
    static final int END_OF_SEGMENT = -1;

    private final Path dir;
    private final int segmentBytes;
    private final CRC32C crc = new CRC32C();
    private final byte[] scratch = new byte[InputCodec.MAX_LENGTH];
    private final UnsafeBuffer segment = new UnsafeBuffer(0, 0);
    private volatile MappedByteBuffer mapped;
    private final ConcurrentLinkedQueue<MappedByteBuffer> retired = new ConcurrentLinkedQueue<>();
    private int segmentIndex;
    private int position;
    private long recordCount;

    private Journal(Path dir, int segmentBytes) {
        this.dir = dir;
        this.segmentBytes = segmentBytes;
    }

    /** Opens (recovering if needed) or creates a journal in {@code dir}. */
    public static Journal open(Path dir, int segmentBytes) {
        if (segmentBytes < 4096 || segmentBytes % 8 != 0) {
            throw new IllegalArgumentException("segmentBytes must be >= 4096 and a multiple of 8");
        }
        Journal j = new Journal(dir, segmentBytes);
        try {
            Files.createDirectories(dir);
            List<Path> segments = segments(dir);
            if (segments.isEmpty()) {
                j.map(0, true);
            } else {
                long[] previous = {0};
                for (int i = 0; i < segments.size(); i++) {
                    boolean last = i == segments.size() - 1;
                    Scan scan = scan(segments.get(i), i, last, previous, null);
                    j.recordCount += scan.records;
                    if (last) {
                        j.map(i, false);
                        j.position = scan.end;
                        j.segment.setMemory(scan.end, segmentBytes - scan.end, (byte) 0); // clear any torn tail
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return j;
    }

    public long recordCount() {
        return recordCount;
    }

    public void append(DirectBuffer buffer, int offset, int length) {
        int needed = RECORD_HEADER + align8(length);
        if (position + needed + RECORD_HEADER > segmentBytes) {
            segment.putInt(position, END_OF_SEGMENT, InputCodec.ORDER);
            map(segmentIndex + 1, true);
        }
        buffer.getBytes(offset, scratch, 0, length);
        crc.reset();
        crc.update(scratch, 0, length);
        segment.putBytes(position + RECORD_HEADER, scratch, 0, length);
        segment.putInt(position + 4, (int) crc.getValue(), InputCodec.ORDER);
        segment.putIntRelease(position, length); // length last: a torn record reads as end or bad CRC; tailers see it complete
        position += needed;
        recordCount++;
    }

    /** Forces appended data to disk, including any segment finished since the last call. Thread-safe. */
    public void flush() {
        for (MappedByteBuffer old = retired.poll(); old != null; old = retired.poll()) {
            old.force();
        }
        mapped.force();
    }

    @Override
    public void close() {
        flush();
    }

    private void map(int index, boolean create) {
        Path file = dir.resolve(String.format("journal-%06d.seg", index));
        MappedByteBuffer next;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            next = ch.map(FileChannel.MapMode.READ_WRITE, 0, segmentBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (mapped != null) {
            retired.add(mapped);
        }
        mapped = next;
        segment.wrap(next);
        segmentIndex = index;
        if (create) {
            for (int p = 0; p < segmentBytes; p += 4096) {
                segment.putByte(p, (byte) 0); // pre-touch
            }
            segment.putLong(0, MAGIC, InputCodec.ORDER);
            segment.putInt(8, VERSION, InputCodec.ORDER);
            segment.putInt(12, index, InputCodec.ORDER);
        }
        position = SEGMENT_HEADER;
    }

    static int align8(int n) {
        return (n + 7) & ~7;
    }

    private static List<Path> segments(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> list = new ArrayList<>(s.filter(p -> p.getFileName().toString().matches("journal-\\d{6}\\.seg"))
                    .sorted().toList());
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).getFileName().toString().equals(String.format("journal-%06d.seg", i))) {
                    throw new IllegalStateException("Missing journal segment " + i + " in " + dir);
                }
            }
            return list;
        }
    }

    record Scan(int end, long records) {}

    private static UnsafeBuffer mapReadOnly(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"); FileChannel ch = raf.getChannel()) {
            return new UnsafeBuffer(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
    }

    private static void checkHeader(UnsafeBuffer seg, Path file, int expectedIndex) {
        if (seg.capacity() < SEGMENT_HEADER || seg.getLong(0, InputCodec.ORDER) != MAGIC) {
            throw new IllegalStateException("Not a journal segment: " + file);
        }
        if (seg.getInt(8, InputCodec.ORDER) != VERSION) {
            throw new IllegalStateException("Journal segment " + file + " has schema " + seg.getInt(8, InputCodec.ORDER)
                    + "; this build reads schema " + VERSION);
        }
        if (seg.getInt(12, InputCodec.ORDER) != expectedIndex) {
            throw new IllegalStateException("Journal segment " + file + " says it is segment "
                    + seg.getInt(12, InputCodec.ORDER) + ", expected " + expectedIndex);
        }
    }

    private static boolean allZero(UnsafeBuffer seg, int from) {
        for (int p = from; p < seg.capacity(); p++) {
            if (seg.getByte(p) != 0) {
                return false;
            }
        }
        return true;
    }

    /** True if a whole, correctly checksummed record of {@code length} payload bytes is at {@code p}. */
    private static boolean validRecord(UnsafeBuffer seg, int p, int length, CRC32C crc, byte[] tmp) {
        if (length < InputCodec.HEADER || length > InputCodec.MAX_LENGTH || p + RECORD_HEADER + length > seg.capacity()) {
            return false;
        }
        seg.getBytes(p + RECORD_HEADER, tmp, 0, length);
        crc.reset();
        crc.update(tmp, 0, length);
        return (int) crc.getValue() == seg.getInt(p + 4, InputCodec.ORDER)
                && InputCodec.length(seg, p + RECORD_HEADER) == length;
    }

    private static IllegalStateException corrupt(Path file, int position, String why) {
        return new IllegalStateException("Corrupt journal segment " + file + " at offset " + position + ": " + why);
    }

    /**
     * Validates one segment, delivering valid records to {@code handler} (if not null). {@code previous}
     * carries the last sequence seen across segments.
     */
    private static Scan scan(Path file, int index, boolean last, long[] previous, InputHandler handler) throws IOException {
        UnsafeBuffer seg = mapReadOnly(file);
        checkHeader(seg, file, index);
        CRC32C crc = new CRC32C();
        byte[] tmp = new byte[InputCodec.MAX_LENGTH];
        int p = SEGMENT_HEADER;
        long records = 0;
        while (p + RECORD_HEADER <= seg.capacity()) {
            int length = seg.getInt(p, InputCodec.ORDER);
            if (length == 0) {
                if (!last) {
                    throw corrupt(file, p, "a non-final segment ends without a rollover marker");
                }
                if (!allZero(seg, p)) {
                    throw corrupt(file, p, "zero length followed by more data");
                }
                return new Scan(p, records);
            }
            if (length == END_OF_SEGMENT) {
                return new Scan(p, records);
            }
            if (!validRecord(seg, p, length, crc, tmp)) {
                boolean plausible = length >= InputCodec.HEADER && length <= InputCodec.MAX_LENGTH
                        && p + RECORD_HEADER + length <= seg.capacity();
                int extent = p + RECORD_HEADER + (plausible ? align8(length) : 0);
                if (last && plausible && allZero(seg, Math.min(extent, seg.capacity()))) {
                    return new Scan(p, records); // the final record was torn by a crash
                }
                throw corrupt(file, p, "bad record with committed data after it");
            }
            long sequence = InputCodec.sequence(seg, p + RECORD_HEADER);
            if (previous[0] != 0 && sequence != previous[0] + 1) {
                throw corrupt(file, p, "sequence " + sequence + " after " + previous[0]);
            }
            previous[0] = sequence;
            if (handler != null) {
                handler.onInput(seg, p + RECORD_HEADER, length);
            }
            records++;
            p += RECORD_HEADER + align8(length);
        }
        return new Scan(p, records);
    }

    /** Replays every record in order, stopping before a torn final record. Returns the number replayed. */
    public static long replay(Path dir, InputHandler handler) {
        return replayAfter(dir, 0, handler);
    }

    /** Replays every record with a sequence above {@code afterSequence}, skipping whole segments before it. */
    public static long replayAfter(Path dir, long afterSequence, InputHandler handler) {
        try {
            long[] count = {0};
            InputHandler filter = (b, o, l) -> {
                if (InputCodec.sequence(b, o) > afterSequence) {
                    handler.onInput(b, o, l);
                    count[0]++;
                }
            };
            List<Path> segments = segments(dir);
            int first = firstSegmentFor(segments, afterSequence);
            long[] previous = {0};
            for (int i = first; i < segments.size(); i++) {
                scan(segments.get(i), i, i == segments.size() - 1, previous, filter);
            }
            return count[0];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Index of the segment holding {@code afterSequence + 1}, found from each segment's first record. */
    static int firstSegmentFor(List<Path> segments, long afterSequence) throws IOException {
        int first = 0;
        for (int i = 1; i < segments.size(); i++) {
            UnsafeBuffer seg = mapReadOnly(segments.get(i));
            int length = seg.capacity() >= SEGMENT_HEADER + RECORD_HEADER ? seg.getInt(SEGMENT_HEADER, InputCodec.ORDER) : 0;
            if (length < InputCodec.HEADER || InputCodec.sequence(seg, SEGMENT_HEADER + RECORD_HEADER) > afterSequence + 1) {
                break;
            }
            first = i;
        }
        return first;
    }

    static List<Path> listSegments(Path dir) throws IOException {
        return segments(dir);
    }
}
