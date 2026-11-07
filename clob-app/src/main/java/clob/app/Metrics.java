package clob.app;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Counters and gauges, rendered in Prometheus text format. Updated off the hot path (ops thread). */
public final class Metrics {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void inc(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    public long get(String name) {
        AtomicLong v = counters.get(name);
        return v == null ? 0 : v.get();
    }

    public void gauge(String name, LongSupplier supplier) {
        gauges.put(name, supplier);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(counters).forEach((k, v) -> sb.append("# TYPE ").append(k).append(" counter\n")
                .append(k).append(' ').append(v.get()).append('\n'));
        new TreeMap<>(gauges).forEach((k, v) -> sb.append("# TYPE ").append(k).append(" gauge\n")
                .append(k).append(' ').append(v.getAsLong()).append('\n'));
        return sb.toString();
    }
}
