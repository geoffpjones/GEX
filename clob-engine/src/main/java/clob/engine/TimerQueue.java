package clob.engine;

import java.util.Arrays;

/**
 * Binary min-heap of timers ordered by (deadline, insertion sequence), in parallel primitive
 * arrays. Cancelled timers are not removed; the engine validates each timer when it fires.
 */
final class TimerQueue {
    private long[] times;
    private long[] seqs;
    private long[] ids;
    private byte[] kinds;
    private int size;
    private long nextSeq;

    TimerQueue(int capacity) {
        times = new long[capacity];
        seqs = new long[capacity];
        ids = new long[capacity];
        kinds = new byte[capacity];
    }

    void schedule(long time, byte kind, long id) {
        if (size == times.length) {
            int n = size * 2;
            times = Arrays.copyOf(times, n);
            seqs = Arrays.copyOf(seqs, n);
            ids = Arrays.copyOf(ids, n);
            kinds = Arrays.copyOf(kinds, n);
        }
        int i = size++;
        times[i] = time;
        seqs[i] = nextSeq++;
        ids[i] = id;
        kinds[i] = kind;
        siftUp(i);
    }

    boolean isEmpty() {
        return size == 0;
    }

    long peekTime() {
        return times[0];
    }

    byte peekKind() {
        return kinds[0];
    }

    long peekId() {
        return ids[0];
    }

    void pop() {
        size--;
        if (size > 0) {
            copy(size, 0);
            siftDown(0);
        }
    }

    @FunctionalInterface
    interface Filter {
        boolean keep(byte kind, long id);
    }

    /**
     * Drops timers the filter rejects, so cancelled long-dated timers don't accumulate. Remaining
     * timers keep their (deadline, sequence) order, so firing order is unaffected.
     */
    void compact(Filter filter) {
        int w = 0;
        for (int i = 0; i < size; i++) {
            if (filter.keep(kinds[i], ids[i])) {
                if (w != i) {
                    copy(i, w);
                }
                w++;
            }
        }
        size = w;
        for (int i = (size >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    int size() {
        return size;
    }

    void save(java.io.DataOutput out) throws java.io.IOException {
        out.writeLong(nextSeq);
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(times[i]);
            out.writeLong(seqs[i]);
            out.writeLong(ids[i]);
            out.writeByte(kinds[i]);
        }
    }

    void restore(java.io.DataInput in) throws java.io.IOException {
        nextSeq = in.readLong();
        size = in.readInt();
        int capacity = Math.max(times.length, Math.max(size, 1));
        times = new long[capacity];
        seqs = new long[capacity];
        ids = new long[capacity];
        kinds = new byte[capacity];
        for (int i = 0; i < size; i++) {
            times[i] = in.readLong();
            seqs[i] = in.readLong();
            ids[i] = in.readLong();
            kinds[i] = in.readByte();
        }
    }

    private boolean less(int a, int b) {
        return times[a] < times[b] || (times[a] == times[b] && seqs[a] < seqs[b]);
    }

    private void copy(int from, int to) {
        times[to] = times[from];
        seqs[to] = seqs[from];
        ids[to] = ids[from];
        kinds[to] = kinds[from];
    }

    private void swap(int a, int b) {
        long t = times[a]; times[a] = times[b]; times[b] = t;
        long s = seqs[a]; seqs[a] = seqs[b]; seqs[b] = s;
        long d = ids[a]; ids[a] = ids[b]; ids[b] = d;
        byte k = kinds[a]; kinds[a] = kinds[b]; kinds[b] = k;
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!less(i, parent)) {
                return;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            int smallest = left;
            int right = left + 1;
            if (right < size && less(right, left)) {
                smallest = right;
            }
            if (!less(smallest, i)) {
                return;
            }
            swap(i, smallest);
            i = smallest;
        }
    }
}
