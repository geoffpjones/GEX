package clob.sequencer;

/** Source of epoch-nanosecond time for the sequencer. The engine never reads a clock. */
@FunctionalInterface
public interface Clock {
    long nanos();

    /**
     * Wall-clock epoch nanos anchored once at start-up and advanced with {@link System#nanoTime()}, so
     * it is monotonic and allocation-free. Drift against NTP is corrected only on restart (production
     * would discipline it against PTP).
     */
    static Clock system() {
        long anchorEpoch = System.currentTimeMillis() * 1_000_000L;
        long anchorNano = System.nanoTime();
        return () -> anchorEpoch + (System.nanoTime() - anchorNano);
    }
}
