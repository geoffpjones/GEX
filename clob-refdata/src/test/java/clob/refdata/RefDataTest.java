package clob.refdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RefDataTest {
    @Test
    void userCanBeMappedToMultipleAccounts() {
        RefData r = RefData.builder().entitle(1, 10).entitle(1, 11).entitle(2, 10).build();
        assertTrue(r.isEntitled(1, 10));
        assertTrue(r.isEntitled(1, 11));
        assertTrue(r.isEntitled(2, 10));
        assertFalse(r.isEntitled(2, 11));
    }

    @Test
    void instrumentLookupByIdAndKey() {
        Instrument i = Instrument.builder(3, "EUR/USD", "SPOT").build();
        RefData r = RefData.builder().add(i).build();
        assertSame(i, r.instrument(3));
        assertSame(i, r.instrument("EUR/USD", "SPOT"));
        assertSame(null, r.instrument(2));
    }

    @Test
    void rejectsDuplicateInstruments() {
        assertThrows(IllegalArgumentException.class, () -> RefData.builder()
                .add(Instrument.builder(0, "EUR/USD", "SPOT").build())
                .add(Instrument.builder(1, "EUR/USD", "SPOT").build()));
    }

    @Test
    void fixingOccurrenceFollowsDaylightSaving() {
        FixingEvent wmr = new FixingEvent(0, "WMR 4pm", LocalTime.of(16, 0), ZoneId.of("Europe/London"), LocalTime.of(15, 55));
        long hour = 3_600_000_000_000L;
        long winter = wmr.occurrenceOn(LocalDate.of(2026, 1, 15)).fixingNanos();
        long summer = wmr.occurrenceOn(LocalDate.of(2026, 7, 15)).fixingNanos();
        assertEquals(0L, Math.floorMod(winter - 16 * hour, 24 * hour), "16:00 GMT is 16:00 UTC in winter");
        assertEquals(0L, Math.floorMod(summer - 15 * hour, 24 * hour), "16:00 BST is 15:00 UTC in summer");
        assertEquals(5 * 60_000_000_000L, winter - wmr.occurrenceOn(LocalDate.of(2026, 1, 15)).cutoffNanos());
    }

    @Test
    void fixingEventCutoffMustPrecedeFixing() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixingEvent(0, "X", LocalTime.of(16, 0), ZoneId.of("UTC"), LocalTime.of(16, 0)));
    }
}
