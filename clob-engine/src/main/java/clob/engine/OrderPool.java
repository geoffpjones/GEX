package clob.engine;

import java.util.Arrays;

final class OrderPool {
    private final int maxLive;
    private Order[] free;
    private int freeCount;
    private int live;

    OrderPool(int initial, int maxLive) {
        this.maxLive = maxLive;
        free = new Order[initial];
        for (int i = 0; i < initial; i++) {
            free[i] = new Order();
        }
        freeCount = initial;
    }

    /** Returns null when the live-order limit is reached. */
    Order acquire() {
        if (live >= maxLive) {
            return null;
        }
        live++;
        if (freeCount > 0) {
            Order o = free[--freeCount];
            free[freeCount] = null;
            return o;
        }
        return new Order();
    }

    void release(Order o) {
        o.reset();
        live--;
        if (freeCount == free.length) {
            free = Arrays.copyOf(free, free.length * 2);
        }
        free[freeCount++] = o;
    }
}
