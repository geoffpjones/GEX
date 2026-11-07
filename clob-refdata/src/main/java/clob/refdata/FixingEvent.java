package clob.refdata;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A fixing event, e.g. "WMR 16:00 London" with a 15:55 cut-off. Orders for the event match only
 * each other until the cut-off; matched quantity is priced when the rate arrives (at fixing time).
 */
public record FixingEvent(int id, String name, LocalTime time, ZoneId zone, LocalTime cutoff) {
    public FixingEvent {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(cutoff, "cutoff");
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0");
        }
        if (!cutoff.isBefore(time)) {
            throw new IllegalArgumentException("cut-off must be before the fixing time");
        }
    }

    /** Cut-off and fixing instants for a date, as epoch nanoseconds, with daylight saving applied. */
    public Occurrence occurrenceOn(LocalDate date) {
        return new Occurrence(epochNanos(ZonedDateTime.of(date, cutoff, zone)), epochNanos(ZonedDateTime.of(date, time, zone)));
    }

    private static long epochNanos(ZonedDateTime t) {
        return t.toEpochSecond() * 1_000_000_000L + t.getNano();
    }

    public record Occurrence(long cutoffNanos, long fixingNanos) {}
}
