package clob.sequencer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import clob.engine.CancelReason;
import clob.engine.RejectReason;
import clob.engine.SessionState;
import clob.refdata.OrderType;
import clob.refdata.TimeInForce;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R08: stable wire ids and a manifest that binds replay to its configuration. */
class ManifestAndWireTest {
    @TempDir
    Path dir;

    static JournalManifest manifest() {
        return new JournalManifest(Wire.SCHEMA_VERSION, JournalManifest.digest(List.of("instrument 0 A/B SPOT")), 1, 8, "c");
    }

    @Test
    void wireIdsArePinned() {
        assertEquals(0, Wire.ORDER_TYPES.id(OrderType.LIMIT));
        assertEquals(5, Wire.ORDER_TYPES.id(OrderType.FIXING));
        assertEquals(4, Wire.TIFS.id(TimeInForce.IOC));
        assertEquals(1, Wire.SESSION_STATES.id(SessionState.OPEN));
        assertEquals(9, Wire.CANCEL_REASONS.id(CancelReason.FIXING_CUTOFF));
        assertEquals(10, Wire.CANCEL_REASONS.id(CancelReason.SELF_TRADE));
        assertEquals(11, Wire.CANCEL_REASONS.id(CancelReason.HALTED));
        assertEquals(16, Wire.REJECT_REASONS.id(RejectReason.MARKET_HALTED));
        assertNull(Wire.CANCEL_REASONS.of(99), "an id this build does not know decodes to null");
    }

    @Test
    void digestIgnoresCommentsAndSpacing() {
        assertEquals(JournalManifest.digest(List.of("instrument 0  A/B SPOT", "")),
                JournalManifest.digest(List.of("# a comment", "instrument  0 A/B   SPOT # trailing")));
    }

    @Test
    void matchingManifestPasses() {
        manifest().verifyOrCreate(dir);
        manifest().verifyOrCreate(dir);
    }

    @Test
    void changedSeedFails() {
        manifest().verifyOrCreate(dir);
        JournalManifest m = manifest();
        assertThrows(JournalManifest.MismatchException.class, () -> new JournalManifest(m.schemaVersion(), m.refDataDigest(), 2,
                m.maxAccounts(), m.engineCapacity()).verifyOrCreate(dir));
    }

    @Test
    void changedRefDataFails() {
        manifest().verifyOrCreate(dir);
        JournalManifest m = manifest();
        assertThrows(JournalManifest.MismatchException.class, () -> new JournalManifest(m.schemaVersion(),
                JournalManifest.digest(List.of("instrument 0 A/B SPOT minQty=5")), m.randomSeed(), m.maxAccounts(),
                m.engineCapacity()).verifyOrCreate(dir));
    }

    @Test
    void newerSchemaFails() {
        manifest().verifyOrCreate(dir);
        JournalManifest m = manifest();
        assertThrows(JournalManifest.MismatchException.class, () -> new JournalManifest(99, m.refDataDigest(), m.randomSeed(),
                m.maxAccounts(), m.engineCapacity()).verifyOrCreate(dir));
    }

    @Test
    void journalWithoutManifestFails() throws IOException {
        Files.writeString(dir.resolve("journal-000000.seg"), "x");
        assertThrows(JournalManifest.MismatchException.class, () -> manifest().verifyOrCreate(dir));
    }
}
