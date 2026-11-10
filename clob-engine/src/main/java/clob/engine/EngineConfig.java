package clob.engine;

/**
 * Pre-sizing for pools and tables (pools grow past these sizes only above the high-water mark), and
 * the seed for race lotteries. The node records seed and capacities in the journal manifest and
 * refuses to replay a journal under different ones.
 */
public record EngineConfig(int initialOrders, int maxLiveOrders, int initialLevels, int initialLevelsPerSide,
        int initialTimers, long randomSeed) {

    public EngineConfig {
        if (initialOrders < 1 || maxLiveOrders < 1 || initialLevels < 1 || initialLevelsPerSide < 1
                || initialTimers < 1) {
            throw new IllegalArgumentException("sizes must be >= 1");
        }
    }

    public static EngineConfig defaults() {
        return new EngineConfig(16_384, 4_000_000, 4_096, 64, 4_096, 0x5EED_C10BL);
    }
}
