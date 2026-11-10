package clob.sequencer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Follows a live journal from another thread, as a reader of the same files the sequencer appends to.
 * Replication tails the journal instead of consuming a pipeline ring, so a slow or dead standby can
 * only fall behind: it has no way to apply back-pressure to the primary. Starting from a sequence
 * finds the right segment from each segment's first record, so catch-up does not rescan history.
 */
public final class JournalTailer {
    private final Path dir;
    private final long afterSequence;
    private final CRC32C crc = new CRC32C();
    private final byte[] tmp = new byte[InputCodec.MAX_LENGTH];
    private UnsafeBuffer segment;
    private int index = -1;
    private int position;

    public JournalTailer(Path dir, long afterSequence) {
        this.dir = dir;
        this.afterSequence = afterSequence;
    }

    /** Delivers up to {@code limit} new records. Zero means nothing new yet. */
    public int poll(InputHandler handler, int limit) {
        try {
            if (segment == null && !open()) {
                return 0;
            }
            int delivered = 0;
            while (delivered < limit && position + Journal.RECORD_HEADER <= segment.capacity()) {
                int length = segment.getIntAcquire(position);
                if (length == 0) {
                    break;
                }
                if (length == Journal.END_OF_SEGMENT) {
                    if (!advance()) {
                        break;
                    }
                    continue;
                }
                if (length < InputCodec.HEADER || length > InputCodec.MAX_LENGTH) {
                    throw new IllegalStateException("Corrupt journal record at offset " + position + " of segment " + index);
                }
                segment.getBytes(position + Journal.RECORD_HEADER, tmp, 0, length);
                crc.reset();
                crc.update(tmp, 0, length);
                if ((int) crc.getValue() != segment.getInt(position + 4, InputCodec.ORDER)) {
                    throw new IllegalStateException("CRC mismatch at offset " + position + " of segment " + index);
                }
                int at = position + Journal.RECORD_HEADER;
                position += Journal.RECORD_HEADER + Journal.align8(length);
                if (InputCodec.sequence(segment, at) > afterSequence) {
                    handler.onInput(segment, at, length);
                    delivered++;
                }
            }
            return delivered;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean open() throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        List<Path> segments = Journal.listSegments(dir);
        if (segments.isEmpty()) {
            return false;
        }
        index = Journal.firstSegmentFor(segments, afterSequence);
        map(index);
        return true;
    }

    private boolean advance() throws IOException {
        Path next = dir.resolve(String.format("journal-%06d.seg", index + 1));
        if (!Files.exists(next)) {
            return false;
        }
        map(index + 1);
        return true;
    }

    private void map(int segmentIndex) throws IOException {
        Path file = dir.resolve(String.format("journal-%06d.seg", segmentIndex));
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"); FileChannel ch = raf.getChannel()) {
            segment = new UnsafeBuffer(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
        index = segmentIndex;
        position = Journal.SEGMENT_HEADER;
    }
}
