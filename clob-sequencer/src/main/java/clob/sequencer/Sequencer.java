package clob.sequencer;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Gives every input its place in history: a sequence number and a time (epoch nanos, never going
 * backwards even if the clock does), journals it, then forwards it downstream (the engine, directly or
 * over a ring). Because time only enters the engine through stamped inputs, the sequencer also injects
 * a {@link InputCodec#TIME_ADVANCE} input whenever the clock passes the engine's next timer deadline, so
 * GTT expiry, TTL releases, peg ticks, races and fixing cut-offs happen with no order flow. Those
 * inputs are journalled too, so replay sees exactly the same timeline.
 */
public final class Sequencer {
    private final Journal journal;
    private final Clock clock;
    private final InputHandler downstream;
    private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
    private long sequence;
    private long lastTime;
    private long mdIntervalNanos;
    private long nextMdBoundary = Long.MAX_VALUE;
    private volatile long mdLateNanos;
    private LocalTime endOfDayTime;
    private ZoneId endOfDayZone;
    private long nextEndOfDay = Long.MAX_VALUE;

    /** Continues after {@code lastSequence}/{@code lastTime} (0 for a new journal; from recovery otherwise). */
    public Sequencer(Journal journal, Clock clock, InputHandler downstream, long lastSequence, long lastTime) {
        this.journal = journal;
        this.clock = clock;
        this.downstream = downstream;
        this.sequence = lastSequence;
        this.lastTime = lastTime;
    }

    public long lastSequence() {
        return sequence;
    }

    public long lastTime() {
        return lastTime;
    }

    /** Stamps an encoded input in place, journals it and forwards it. */
    public void onCommand(MutableDirectBuffer buffer, int offset, int length) {
        long now = Math.max(clock.nanos(), lastTime);
        lastTime = now;
        InputCodec.stamp(buffer, offset, ++sequence, now);
        journal.append(buffer, offset, length);
        downstream.onInput(buffer, offset, length);
    }

    /**
     * Emits an {@link InputCodec#MD_TICK} at every multiple of {@code intervalNanos} on the wall clock
     * (00:00:00.000, .005, ...). If the sequencer falls behind, missed boundaries are skipped and only the
     * latest is emitted; the lateness is recorded ({@link #maxMdLatenessNanos()}).
     */
    public void enableMarketDataTicks(long intervalNanos) {
        mdIntervalNanos = intervalNanos;
        long now = clock.nanos();
        nextMdBoundary = (Math.floorDiv(now, intervalNanos) + 1) * intervalNanos;
    }

    /**
     * Emits an {@link InputCodec#END_OF_DAY} input at this local time every day; the zone's rules make it
     * DST-aware. The next cut is computed from the last sequenced time, so one that fell while the
     * node was down is applied as soon as it restarts, once, however long the outage was.
     */
    public void enableEndOfDay(LocalTime time, ZoneId zone) {
        endOfDayTime = time;
        endOfDayZone = zone;
        long reference = sequence == 0 ? clock.nanos() : lastTime;
        nextEndOfDay = nextCut(reference);
    }

    private long nextCut(long afterNanos) {
        ZonedDateTime after = Instant.ofEpochSecond(Math.floorDiv(afterNanos, 1_000_000_000L),
                Math.floorMod(afterNanos, 1_000_000_000L)).atZone(endOfDayZone);
        ZonedDateTime cut = after.toLocalDate().atTime(endOfDayTime).atZone(endOfDayZone);
        if (!cut.isAfter(after)) {
            cut = after.toLocalDate().plusDays(1).atTime(endOfDayTime).atZone(endOfDayZone);
        }
        return cut.toEpochSecond() * 1_000_000_000L + cut.getNano();
    }

    public long maxMdLatenessNanos() {
        return mdLateNanos;
    }

    /**
     * Injects any due MD tick, and a time input if the clock has reached {@code nextDeadline}
     * ({@code Long.MAX_VALUE} for none). Returns true if it injected a time input.
     */
    public boolean poll(long nextDeadline) {
        long now = clock.nanos();
        boolean injected = false;
        if (now >= nextMdBoundary) {
            long boundary = Math.floorDiv(now, mdIntervalNanos) * mdIntervalNanos;
            mdLateNanos = Math.max(mdLateNanos, now - boundary);
            nextMdBoundary = boundary + mdIntervalNanos;
            onCommand(scratch, 0, InputCodec.mdTick(scratch, 0, boundary));
        }
        if (now >= nextEndOfDay) {
            nextEndOfDay = nextCut(now);
            onCommand(scratch, 0, InputCodec.endOfDay(scratch, 0));
        }
        if (nextDeadline != Long.MAX_VALUE && now >= nextDeadline) {
            onCommand(scratch, 0, InputCodec.timeAdvance(scratch, 0));
            injected = true;
        }
        return injected;
    }
}
