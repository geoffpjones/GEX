package clob.refdata;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable instrument definition. Prices are in ticks (one unit of {@code pricePrecision});
 * quantities are in base-currency units; times are in nanoseconds.
 *
 * <p>Quantity rule: a quantity is valid when {@code q >= minQty}, {@code q <= maxQty} and
 * {@code (q - minQty) % qtyIncrement == 0}. The constructor additionally requires
 * {@code minQty % qtyIncrement == 0} (PLAN Q6i default), so that every fill and every residual
 * stays on the increment grid.
 */
public record Instrument(
        int id,
        String symbol,
        String tenor,
        InstrumentType type,
        int pricePrecision,
        long priceIncrement,
        long minTtlNanos,
        long minQty,
        long maxQty,
        long qtyIncrement,
        long minShow,
        long mdPriceIncrement,
        long mdIntervalNanos,
        int mdLevels,
        long pegThrottleNanos,
        long midTtlNanos,
        Set<TimeInForce> supportedTifs,
        Set<OrderType> supportedOrderTypes,
        MatchPriority matchPriority,
        long makerRaceNanos,
        long takerRaceNanos,
        boolean queueCancels,
        boolean rejectOnTtl) {

    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(tenor, "tenor");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(matchPriority, "matchPriority");
        require(id >= 0, "id must be >= 0");
        require(!symbol.isBlank() && !tenor.isBlank(), "symbol and tenor are required");
        require(pricePrecision >= 0 && pricePrecision <= 12, "pricePrecision must be 0..12");
        require(priceIncrement > 0, "priceIncrement must be > 0");
        require(minQty > 0, "minQty must be > 0");
        require(qtyIncrement > 0, "qtyIncrement must be > 0");
        require(minQty % qtyIncrement == 0, "minQty must be a multiple of qtyIncrement (PLAN Q6i)");
        require(maxQty >= minQty, "maxQty must be >= minQty");
        require((maxQty - minQty) % qtyIncrement == 0, "maxQty must be on the qtyIncrement grid");
        require(minShow >= 0, "minShow must be >= 0");
        require(mdPriceIncrement > 0 && mdPriceIncrement % priceIncrement == 0,
                "mdPriceIncrement must be a positive multiple of priceIncrement");
        require(mdLevels > 0, "mdLevels must be > 0");
        require(minTtlNanos >= 0 && midTtlNanos >= 0 && mdIntervalNanos >= 0 && pegThrottleNanos >= 0
                && makerRaceNanos >= 0 && takerRaceNanos >= 0, "time parameters must be >= 0");
        Objects.requireNonNull(supportedTifs, "supportedTifs");
        Objects.requireNonNull(supportedOrderTypes, "supportedOrderTypes");
        require(!supportedTifs.isEmpty(), "at least one TIF must be supported");
        require(!supportedOrderTypes.isEmpty(), "at least one order type must be supported");
        supportedTifs = Collections.unmodifiableSet(EnumSet.copyOf(supportedTifs));
        supportedOrderTypes = Collections.unmodifiableSet(EnumSet.copyOf(supportedOrderTypes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** SPOT and NDF prices are outright and must be positive; SWAP prices are signed points. */
    public boolean isValidPrice(long price) {
        if (type != InstrumentType.SWAP && price <= 0) {
            return false;
        }
        return price % priceIncrement == 0;
    }

    public boolean isValidQty(long qty) {
        return qty <= maxQty && isValidExecutionSize(qty);
    }

    /** Valid size for a fill, residual or display slice: at least minQty and on the increment grid. */
    public boolean isValidExecutionSize(long qty) {
        return qty >= minQty && (qty - minQty) % qtyIncrement == 0;
    }

    /**
     * Show (max floor) validity. {@code show >= qty} means fully visible. Otherwise the show must
     * respect minShow, and a non-zero show must itself be a valid execution size, so that every fill
     * against a displayed slice satisfies min quantity (PLAN Q27 default).
     */
    public boolean isValidShow(long show, long qty) {
        if (show < 0) {
            return false;
        }
        if (show >= qty) {
            return true;
        }
        if (show < minShow) {
            return false;
        }
        return show == 0 || isValidExecutionSize(show);
    }

    public String key() {
        return symbol + " " + tenor;
    }

    public static Builder builder(int id, String symbol, String tenor) {
        return new Builder(id, symbol, tenor);
    }

    /** Builder with defaults matching the expected production configuration. */
    public static final class Builder {
        private final int id;
        private final String symbol;
        private final String tenor;
        private InstrumentType type = InstrumentType.SPOT;
        private int pricePrecision = 5;
        private long priceIncrement = 1;
        private long minTtlNanos = 5_000_000L;
        private long minQty = 1_000_000L;
        private long maxQty = 1_000_000_000L;
        private long qtyIncrement = 100_000L;
        private long minShow = 0;
        private long mdPriceIncrement = 1;
        private long mdIntervalNanos = 5_000_000L;
        private int mdLevels = 5;
        private long pegThrottleNanos = 20_000_000L;
        private long midTtlNanos = 0;
        private Set<TimeInForce> supportedTifs = EnumSet.allOf(TimeInForce.class);
        private Set<OrderType> supportedOrderTypes = EnumSet.allOf(OrderType.class);
        private MatchPriority matchPriority = MatchPriority.VISIBILITY;
        private long makerRaceNanos = 0;
        private long takerRaceNanos = 0;
        private boolean queueCancels = false;
        private boolean rejectOnTtl = false;

        private Builder(int id, String symbol, String tenor) {
            this.id = id;
            this.symbol = symbol;
            this.tenor = tenor;
        }

        public Builder type(InstrumentType v) { this.type = v; return this; }
        public Builder pricePrecision(int v) { this.pricePrecision = v; return this; }
        public Builder priceIncrement(long v) { this.priceIncrement = v; return this; }
        public Builder minTtlNanos(long v) { this.minTtlNanos = v; return this; }
        public Builder minQty(long v) { this.minQty = v; return this; }
        public Builder maxQty(long v) { this.maxQty = v; return this; }
        public Builder qtyIncrement(long v) { this.qtyIncrement = v; return this; }
        public Builder minShow(long v) { this.minShow = v; return this; }
        public Builder mdPriceIncrement(long v) { this.mdPriceIncrement = v; return this; }
        public Builder mdIntervalNanos(long v) { this.mdIntervalNanos = v; return this; }
        public Builder mdLevels(int v) { this.mdLevels = v; return this; }
        public Builder pegThrottleNanos(long v) { this.pegThrottleNanos = v; return this; }
        public Builder midTtlNanos(long v) { this.midTtlNanos = v; return this; }
        public Builder supportedTifs(Set<TimeInForce> v) { this.supportedTifs = v; return this; }
        public Builder supportedOrderTypes(Set<OrderType> v) { this.supportedOrderTypes = v; return this; }
        public Builder matchPriority(MatchPriority v) { this.matchPriority = v; return this; }
        public Builder makerRaceNanos(long v) { this.makerRaceNanos = v; return this; }
        public Builder takerRaceNanos(long v) { this.takerRaceNanos = v; return this; }
        public Builder queueCancels(boolean v) { this.queueCancels = v; return this; }
        public Builder rejectOnTtl(boolean v) { this.rejectOnTtl = v; return this; }

        public Instrument build() {
            return new Instrument(id, symbol, tenor, type, pricePrecision, priceIncrement, minTtlNanos, minQty,
                    maxQty, qtyIncrement, minShow, mdPriceIncrement, mdIntervalNanos, mdLevels, pegThrottleNanos,
                    midTtlNanos, supportedTifs, supportedOrderTypes, matchPriority, makerRaceNanos, takerRaceNanos,
                    queueCancels, rejectOnTtl);
        }
    }
}
