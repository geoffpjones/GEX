package clob.engine;

import clob.common.Side;
import java.util.Arrays;

/**
 * Price levels for one side, held in a dense array sorted best-first. FX books are shallow, so
 * binary search plus {@code System.arraycopy} beats a tree on cache behaviour and allocates nothing.
 */
final class BookSide {
    final Side side;
    private PriceLevel[] levels;
    private int size;

    BookSide(Side side, int initialCapacity) {
        this.side = side;
        this.levels = new PriceLevel[initialCapacity];
    }

    int size() {
        return size;
    }

    PriceLevel levelAt(int index) {
        return levels[index];
    }

    /** Ascending key order is best-first. */
    private long key(long price) {
        return side == Side.BUY ? -price : price;
    }

    /** Index of the level at {@code price}, or {@code -(insertionPoint + 1)}. */
    int search(long price) {
        long k = key(price);
        int lo = 0;
        int hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long mk = key(levels[mid].price);
            if (mk < k) {
                lo = mid + 1;
            } else if (mk > k) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    PriceLevel find(long price) {
        int i = search(price);
        return i >= 0 ? levels[i] : null;
    }

    PriceLevel getOrCreate(long price, LevelPool pool) {
        int i = search(price);
        if (i >= 0) {
            return levels[i];
        }
        int insertAt = -(i + 1);
        if (size == levels.length) {
            levels = Arrays.copyOf(levels, size * 2);
        }
        System.arraycopy(levels, insertAt, levels, insertAt + 1, size - insertAt);
        PriceLevel level = pool.acquire(price);
        levels[insertAt] = level;
        size++;
        return level;
    }

    void removeAt(int index, LevelPool pool) {
        PriceLevel level = levels[index];
        System.arraycopy(levels, index + 1, levels, index, size - index - 1);
        levels[--size] = null;
        pool.release(level);
    }

    void removeIfEmpty(PriceLevel level, LevelPool pool) {
        if (level.isEmpty()) {
            removeAt(search(level.price), pool);
        }
    }

    /** Best price with displayed non-peg quantity, or {@code Long.MIN_VALUE} if none. */
    long bestNonPegVisible() {
        for (int i = 0; i < size; i++) {
            if (levels[i].hasNonPegVisible()) {
                return levels[i].price;
            }
        }
        return Long.MIN_VALUE;
    }

    /** Index of the best level with displayed quantity, or -1. */
    int firstVisibleIndex() {
        for (int i = 0; i < size; i++) {
            if (levels[i].visHead != null) {
                return i;
            }
        }
        return -1;
    }
}
