package clob.engine;

/**
 * Deterministic PRNG for race lotteries (SplitMix64). Reseeded per race from the configured seed and
 * the race id, so replaying the journal reproduces every draw. The seed is published in
 * {@link EngineListener#onRaceStarted} for audit.
 */
final class SplitMix64 {
    private long state;

    void seed(long seed) {
        state = seed;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    long nextLong() {
        state += 0x9e3779b97f4a7c15L;
        return mix(state);
    }

    /** Uniform in [0, bound), without modulo bias. */
    int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be > 0");
        }
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound) - 1;
        long r;
        do {
            r = nextLong() >>> 1;
        } while (r > limit);
        return (int) (r % bound);
    }
}
