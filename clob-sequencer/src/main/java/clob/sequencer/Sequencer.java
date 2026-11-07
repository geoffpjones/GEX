package clob.sequencer;

import java.nio.ByteBuffer;
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

    /** Injects a time input if the clock has reached {@code nextDeadline}. Returns true if it did. */
    public boolean poll(long nextDeadline) {
        if (nextDeadline == Long.MAX_VALUE || clock.nanos() < nextDeadline) {
            return false;
        }
        onCommand(scratch, 0, InputCodec.timeAdvance(scratch, 0));
        return true;
    }
}
