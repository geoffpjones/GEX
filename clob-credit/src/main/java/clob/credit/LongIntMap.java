package clob.credit;

import java.util.Arrays;

/** Open-addressing long→int map with a strong mixer (structured keys cluster badly otherwise). */
final class LongIntMap {
    private final int missing;
    private long[] keys;
    private int[] values;
    private boolean[] used;
    private int size;
    private int mask;

    LongIntMap(int capacity, int missing) {
        int cap = Integer.highestOneBit(Math.max(16, capacity * 2 - 1)) << 1;
        this.missing = missing;
        keys = new long[cap];
        values = new int[cap];
        used = new boolean[cap];
        mask = cap - 1;
    }

    private static int mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k;
    }

    int get(long key) {
        int i = mix(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
        return missing;
    }

    void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int i = mix(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        int cap = oldKeys.length * 2;
        keys = new long[cap];
        values = new int[cap];
        used = new boolean[cap];
        mask = cap - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    int size() {
        return size;
    }

    @FunctionalInterface
    interface EntryConsumer {
        void accept(long key, int value) throws java.io.IOException;
    }

    /** Visits every entry. Order follows the table, so callers must not depend on it. */
    void forEach(EntryConsumer consumer) throws java.io.IOException {
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                consumer.accept(keys[i], values[i]);
            }
        }
    }

    void clear() {
        Arrays.fill(used, false);
        size = 0;
    }
}
