package clob.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Counters and gauges, rendered in Prometheus text format. A sample name may include labels, as in
 * {@code clob_cancels_total{reason="ADMIN"}}; each metric family gets one TYPE line, followed by its samples.
 */
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

    private static String family(String sample) {
        int brace = sample.indexOf('{');
        return brace < 0 ? sample : sample.substring(0, brace);
    }

    private static void renderFamilies(StringBuilder sb, String type, TreeMap<String, List<String>> families) {
        families.forEach((family, samples) -> {
            sb.append("# TYPE ").append(family).append(' ').append(type).append('\n');
            samples.forEach(line -> sb.append(line).append('\n'));
        });
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        TreeMap<String, List<String>> counterFamilies = new TreeMap<>();
        new TreeMap<>(counters).forEach((k, v) ->
                counterFamilies.computeIfAbsent(family(k), f -> new ArrayList<>()).add(k + " " + v.get()));
        TreeMap<String, List<String>> gaugeFamilies = new TreeMap<>();
        new TreeMap<>(gauges).forEach((k, v) ->
                gaugeFamilies.computeIfAbsent(family(k), f -> new ArrayList<>()).add(k + " " + v.getAsLong()));
        renderFamilies(sb, "counter", counterFamilies);
        renderFamilies(sb, "gauge", gaugeFamilies);
        return sb.toString();
    }
}
