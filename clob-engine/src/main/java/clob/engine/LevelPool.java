package clob.engine;

import java.util.Arrays;

final class LevelPool {
    private PriceLevel[] free;
    private int freeCount;

    LevelPool(int initial) {
        free = new PriceLevel[initial];
        for (int i = 0; i < initial; i++) {
            free[i] = new PriceLevel();
        }
        freeCount = initial;
    }

    PriceLevel acquire(long price) {
        PriceLevel level;
        if (freeCount > 0) {
            level = free[--freeCount];
            free[freeCount] = null;
        } else {
            level = new PriceLevel();
        }
        level.reset(price);
        return level;
    }

    void release(PriceLevel level) {
        level.reset(0);
        if (freeCount == free.length) {
            free = Arrays.copyOf(free, free.length * 2);
        }
        free[freeCount++] = level;
    }
}
