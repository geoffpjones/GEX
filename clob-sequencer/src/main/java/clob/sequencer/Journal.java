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
 * On open, existing segments are scanned. A bad record (CRC mismatch, impossible length) in the last
 * segment is a torn write from a crash: the journal is truncated there and appending resumes. A bad
 * record in an earlier segment is corruption and fails the open.
 *
 * Durability: appends land in the page cache (they survive a process crash). {@link #flush()} forces
 * them to disk; production would flush on a short interval or per batch (Aeron Archive replicates
 * instead).
 */
public final class Journal implements AutoCloseable {
    static final int SEGMENT_HEADER = 64;
    static final long MAGIC = 0x314C4E4A424F4C43L; // "CLOBJNL1" little-endian
    static final int VERSION = 1;
    static final int RECORD_HEADER = 8;
    static final int END_OF_SEGMENT = -1;

    private final Path dir;
    private final int segmentBytes;
    private final CRC32C crc = new CRC32C();
    private final byte[] scratch = new byte[InputCodec.MAX_LENGTH];
    private final UnsafeBuffer segment = new UnsafeBuffer(0, 0);
    private MappedByteBuffer mapped;
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
                for (int i = 0; i < segments.size(); i++) {
                    boolean last = i == segments.size() - 1;
                    Scan scan = scan(segments.get(i), null);
                    j.recordCount += scan.records;
                    if (!last && !scan.clean) {
                        throw new IllegalStateException("Corrupt journal segment " + segments.get(i));
                    }
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
        segment.putInt(position, length, InputCodec.ORDER); // length last: a torn record reads as end or bad CRC
        position += needed;
        recordCount++;
    }

    public void flush() {
        mapped.force();
    }

    @Override
    public void close() {
        flush();
    }

    private void map(int index, boolean create) {
        Path file = dir.resolve(String.format("journal-%06d.seg", index));
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            mapped = ch.map(FileChannel.MapMode.READ_WRITE, 0, segmentBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        segment.wrap(mapped);
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

    record Scan(int end, long records, boolean clean) {}

    /** Reads one segment, delivering valid records to {@code handler} (if not null). */
    private static Scan scan(Path file, InputHandler handler) throws IOException {
        UnsafeBuffer seg;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"); FileChannel ch = raf.getChannel()) {
            seg = new UnsafeBuffer(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
        if (seg.capacity() < SEGMENT_HEADER || seg.getLong(0, InputCodec.ORDER) != MAGIC) {
            throw new IllegalStateException("Not a journal segment: " + file);
        }
        CRC32C crc = new CRC32C();
        byte[] tmp = new byte[InputCodec.MAX_LENGTH];
        int p = SEGMENT_HEADER;
        long records = 0;
        while (p + RECORD_HEADER <= seg.capacity()) {
            int length = seg.getInt(p, InputCodec.ORDER);
            if (length == 0) {
                return new Scan(p, records, true);
            }
            if (length == END_OF_SEGMENT) {
                return new Scan(p, records, true);
            }
            if (length < InputCodec.HEADER || length > InputCodec.MAX_LENGTH
                    || p + RECORD_HEADER + length > seg.capacity()) {
                return new Scan(p, records, false);
            }
            seg.getBytes(p + RECORD_HEADER, tmp, 0, length);
            crc.reset();
            crc.update(tmp, 0, length);
            if ((int) crc.getValue() != seg.getInt(p + 4, InputCodec.ORDER)
                    || InputCodec.length(seg, p + RECORD_HEADER) != length) {
                return new Scan(p, records, false);
            }
            if (handler != null) {
                handler.onInput(seg, p + RECORD_HEADER, length);
            }
            records++;
            p += RECORD_HEADER + align8(length);
        }
        return new Scan(p, records, true);
    }

    /**
     * Replays every valid record in order, stopping at the first torn record of the last segment.
     * Returns the number of records replayed.
     */
    public static long replay(Path dir, InputHandler handler) {
        try {
            long n = 0;
            List<Path> segments = segments(dir);
            for (int i = 0; i < segments.size(); i++) {
                Scan scan = scan(segments.get(i), handler);
                n += scan.records;
                if (!scan.clean && i < segments.size() - 1) {
                    throw new IllegalStateException("Corrupt journal segment " + segments.get(i));
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
