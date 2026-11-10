package clob.app;

import clob.refdata.FixingEvent;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

/**
 * Reference data from a text file. One entry per line; {@code #} starts a comment.
 *
 * <pre>
 * instrument &lt;id&gt; &lt;symbol&gt; &lt;tenor&gt; [key=value ...]
 *   keys: type, precision, priceIncrement, minQty, maxQty, qtyIncrement, minShow, mdPriceIncrement,
 *         mdLevels, mdIntervalMs, minTtlMicros, midTtlMicros, pegThrottleMs, takerRaceMicros,
 *         makerRaceMicros, queueCancels, rejectOnTtl, matchPriority,
 *         orderTypes (comma-separated, e.g. LIMIT,ICEBERG), tifs (comma-separated, e.g. DAY,GTC,IOC)
 * entitle &lt;userId&gt; &lt;accountId&gt;
 * entity &lt;accountId&gt; &lt;legalEntityId&gt;
 * fixing &lt;id&gt; &lt;name&gt; &lt;HH:mm&gt; &lt;zone&gt; &lt;cutoff HH:mm&gt;
 * </pre>
 */
public final class RefDataLoader {
    private RefDataLoader() {}

    public static RefData load(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    public static RefData parse(List<String> lines) {
        RefData.Builder b = RefData.builder();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            String[] t = line.trim().split("\\s+");
            if (t.length == 0 || t[0].isEmpty()) {
                continue;
            }
            try {
                switch (t[0]) {
                    case "instrument" -> b.add(instrument(t));
                    case "entitle" -> b.entitle(Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                    case "entity" -> b.entity(Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                    case "fixing" -> b.addFixingEvent(new FixingEvent(Integer.parseInt(t[1]), t[2], LocalTime.parse(t[3]),
                            ZoneId.of(t[4]), LocalTime.parse(t[5])));
                    default -> throw new IllegalArgumentException("unknown entry '" + t[0] + "'");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("refdata line " + n + ": " + e.getMessage(), e);
            }
        }
        return b.build();
    }

    private static Instrument instrument(String[] t) {
        Instrument.Builder b = Instrument.builder(Integer.parseInt(t[1]), t[2], t[3]);
        for (int i = 4; i < t.length; i++) {
            String[] kv = t[i].split("=", 2);
            String v = kv[1];
            switch (kv[0]) {
                case "type" -> b.type(InstrumentType.valueOf(v));
                case "precision" -> b.pricePrecision(Integer.parseInt(v));
                case "priceIncrement" -> b.priceIncrement(Long.parseLong(v));
                case "minQty" -> b.minQty(Long.parseLong(v));
                case "maxQty" -> b.maxQty(Long.parseLong(v));
                case "qtyIncrement" -> b.qtyIncrement(Long.parseLong(v));
                case "minShow" -> b.minShow(Long.parseLong(v));
                case "mdPriceIncrement" -> b.mdPriceIncrement(Long.parseLong(v));
                case "mdLevels" -> b.mdLevels(Integer.parseInt(v));
                case "mdIntervalMs" -> b.mdIntervalNanos(Math.multiplyExact(Long.parseLong(v), 1_000_000L));
                case "minTtlMicros" -> b.minTtlNanos(Math.multiplyExact(Long.parseLong(v), 1_000L));
                case "midTtlMicros" -> b.midTtlNanos(Math.multiplyExact(Long.parseLong(v), 1_000L));
                case "pegThrottleMs" -> b.pegThrottleNanos(Math.multiplyExact(Long.parseLong(v), 1_000_000L));
                case "takerRaceMicros" -> b.takerRaceNanos(Math.multiplyExact(Long.parseLong(v), 1_000L));
                case "makerRaceMicros" -> b.makerRaceNanos(Math.multiplyExact(Long.parseLong(v), 1_000L));
                case "orderTypes" -> b.supportedOrderTypes(parseSet(OrderType.class, v));
                case "tifs" -> b.supportedTifs(parseSet(TimeInForce.class, v));
                case "queueCancels" -> b.queueCancels(Boolean.parseBoolean(v));
                case "rejectOnTtl" -> b.rejectOnTtl(Boolean.parseBoolean(v));
                case "matchPriority" -> b.matchPriority(MatchPriority.valueOf(v));
                default -> throw new IllegalArgumentException("unknown instrument key '" + kv[0] + "'");
            }
        }
        Instrument instrument = b.build();
        if (instrument.supportedOrderTypes().contains(OrderType.PEGGED) && instrument.pegThrottleNanos() == 0) {
            throw new IllegalArgumentException("pegged orders are enabled but pegThrottleMs is 0");
        }
        if (instrument.supportedOrderTypes().equals(EnumSet.of(OrderType.MARKET))
                && instrument.supportedTifs().stream().noneMatch(tif -> tif == TimeInForce.IOC || tif == TimeInForce.FOK)) {
            throw new IllegalArgumentException("market orders need IOC or FOK");
        }
        return instrument;
    }

    private static <E extends Enum<E>> EnumSet<E> parseSet(Class<E> type, String list) {
        EnumSet<E> set = EnumSet.noneOf(type);
        for (String item : list.split(",")) {
            set.add(Enum.valueOf(type, item.trim()));
        }
        return set;
    }
}
