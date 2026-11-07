package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
    @TempDir
    Path dir;

    private static List<byte[]> messages(int n) {
        Fixture f = new Fixture().admin(3).flow(3, n);
        return f.inputs.subList(0, Math.min(n, f.inputs.size()));
    }

    private static List<byte[]> read(Path dir) {
        List<byte[]> out = new ArrayList<>();
        Journal.replay(dir, (b, o, l) -> {
            byte[] m = new byte[l];
            b.getBytes(o, m);
            out.add(m);
        });
        return out;
    }

    private static void write(Journal j, List<byte[]> msgs) {
        long seq = 0;
        for (byte[] m : msgs) {
            var b = Fixture.wrap(m);
            InputCodec.stamp(b, 0, ++seq, seq);
            j.append(b, 0, m.length);
        }
    }

    @Test
    void appendAndReplayAcrossSegments() throws IOException {
        List<byte[]> msgs = messages(2000);
        try (Journal j = Journal.open(dir, 8192)) {
            write(j, msgs);
        }
        try (var files = Files.list(dir)) {
            assertTrue(files.count() > 5, "rotated into several segments");
        }
        List<byte[]> back = read(dir);
        assertEquals(msgs.size(), back.size());
        for (int i = 0; i < msgs.size(); i++) {
            assertEquals(i + 1, InputCodec.sequence(Fixture.wrap(back.get(i)), 0));
            assertEquals(InputCodec.type(Fixture.wrap(msgs.get(i)), 0), InputCodec.type(Fixture.wrap(back.get(i)), 0));
        }
        assertEquals(msgs.size(), Journal.open(dir, 8192).recordCount());
    }

    @Test
    void tornTailIsTruncatedAndAppendingResumes() throws IOException {
        List<byte[]> msgs = messages(50);
        try (Journal j = Journal.open(dir, 1 << 16)) {
            write(j, msgs);
        }
        Path seg = dir.resolve("journal-000000.seg");
        // Corrupt the last record's payload, as a crash mid-write would.
        List<byte[]> before = read(dir);
        long offset = Journal.SEGMENT_HEADER;
        for (int i = 0; i < before.size() - 1; i++) {
            offset += Journal.RECORD_HEADER + Journal.align8(before.get(i).length);
        }
        try (RandomAccessFile raf = new RandomAccessFile(seg.toFile(), "rw")) {
            raf.seek(offset + Journal.RECORD_HEADER + 30);
            raf.write(0x5A);
        }
        assertEquals(msgs.size() - 1, read(dir).size(), "replay stops at the torn record");
        try (Journal j = Journal.open(dir, 1 << 16)) {
            assertEquals(msgs.size() - 1, j.recordCount());
            var b = Fixture.wrap(msgs.get(0));
            j.append(b, 0, msgs.get(0).length);
        }
        List<byte[]> after = read(dir);
        assertEquals(msgs.size(), after.size());
        assertArrayEquals(msgs.get(0), after.get(after.size() - 1));
    }

    @Test
    void corruptionInAnEarlierSegmentFailsTheOpen() throws IOException {
        try (Journal j = Journal.open(dir, 8192)) {
            write(j, messages(1000));
        }
        try (RandomAccessFile raf = new RandomAccessFile(dir.resolve("journal-000000.seg").toFile(), "rw")) {
            raf.seek(Journal.SEGMENT_HEADER + Journal.RECORD_HEADER + 10);
            raf.write(0x5A);
        }
        assertThrows(IllegalStateException.class, () -> Journal.open(dir, 8192));
        assertThrows(IllegalStateException.class, () -> read(dir));
    }
}
