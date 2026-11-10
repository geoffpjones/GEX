package clob.credit;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.RefData;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Bilateral credit: relationships, lines (pools) and exposure accounting. Owned by the engine's
 * single thread; every mutation arrives as a sequenced input, so replay is deterministic.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li>A <b>relationship</b> from account A to account B says which line measures the credit A
 *       extends to B, with a default plus per-instrument overrides. {@link #NO_LINE} means no credit.
 *   <li>A <b>line</b> is a pool of credit extended by one account (its giver), with a type
 *       ({@link CreditType}) and a USD limit. Several of the giver's relationships and instruments
 *       may map to one line and share its usage (PLAN Q6g). Because a line has a single giver, credit
 *       can only tighten during one aggression, which keeps FOK dry runs exact.
 *   <li>A and B can trade an instrument only if A→B and B→A both have a line (confirmed).
 *   <li><b>Exposure</b> of a fill is the base-currency notional converted at the base currency's
 *       USD rate. SPOT and NDF have one leg on the instrument's value date. A SWAP has two legs:
 *       the far leg in the order's direction and the near leg opposite (confirmed convention).
 *   <li><b>DSL</b> adds the gross USD amount of each leg to its value date and checks each date.
 *       <b>NOP</b> adds the signed amount (+ when the line's giver buys base) and checks
 *       {@code |net| <= limit} across all open dates. A SWAP's legs therefore net to zero for NOP
 *       (PLAN Q6b default). Trades that reduce |net| are always allowed, even over the limit.
 * </ul>
 *
 * <h2>Arithmetic</h2>
 * USD amounts are held exactly as {@code qty × rateE8}, where {@code rateE8} is USD per base unit
 * × {@value #RATE_SCALE}. With no rounding, capacity falls by exactly the quantity filled, so a
 * dry run (FOK) and real matching always agree. Usage is recorded at the rate in force at the trade
 * and is not revalued later (PLAN Q6d/Q6h default). Limits are capped at {@value #MAX_LIMIT_USD}
 * USD and rates at {@value #MAX_RATE_E8} (10 USD per unit) so that the products fit in a long. DSL
 * lines record only gross usage and NOP lines only signed usage, so turnover cannot grow without
 * bound, and {@link #capacity} clips a fill to what every value-date bucket can hold. Commit
 * re-checks that bound for both lines before changing either, so a fill is all or nothing.
 *
 * <h2>Refusals</h2>
 * Admin methods validate everything first and return a {@link CreditRefusal} (null when applied):
 * a refused input changes nothing.
 */
public final class CreditEngine {
    public static final int NO_LINE = -1;
    /** Relationship key for "every instrument without an override". */
    public static final int ALL_INSTRUMENTS = -1;
    public static final long RATE_SCALE = 100_000_000L;
    public static final long MAX_LIMIT_USD = 10_000_000_000L;
    public static final long MAX_RATE_E8 = 1_000_000_000L;
    public static final long UNLIMITED_CAPACITY = Long.MAX_VALUE;
    /** Largest absolute signed amount one value date of a line may hold. Capacity clips fills to it. */
    public static final long MAX_BUCKET_E8 = 4_000_000_000_000_000_000L;
    public static final int MAX_LINE_ID = 1 << 20;
    /** No business date has been established yet. */
    public static final int NO_DATE = -1;

    private static final int NOT_OVERRIDDEN = -2;

    /**
     * Dry-run usage kept apart from the real accounting, so one aggression's fills tighten credit
     * without changing it. Reads of the engine are safe from several threads while it is not being
     * written; each thread uses its own overlay.
     */
    public static final class Overlay {
        long[] grossNear = new long[64];
        long[] grossFar = new long[64];
        long[] net = new long[64];
        long[] netNear = new long[64];
        long[] netFar = new long[64];
        boolean[] used = new boolean[64];
        int[] touched = new int[64];
        int count;

        void ensure(int lines) {
            if (lines > used.length) {
                int n = Math.max(lines, used.length * 2);
                grossNear = Arrays.copyOf(grossNear, n);
                grossFar = Arrays.copyOf(grossFar, n);
                net = Arrays.copyOf(net, n);
                netNear = Arrays.copyOf(netNear, n);
                netFar = Arrays.copyOf(netFar, n);
                used = Arrays.copyOf(used, n);
                touched = Arrays.copyOf(touched, n);
            }
        }

        void touch(int lineId) {
            if (!used[lineId]) {
                used[lineId] = true;
                if (count == touched.length) {
                    touched = Arrays.copyOf(touched, count * 2);
                }
                touched[count++] = lineId;
            }
        }

        /** Forgets everything recorded. */
        public void clear() {
            for (int i = 0; i < count; i++) {
                int id = touched[i];
                used[id] = false;
                grossNear[id] = 0;
                grossFar[id] = 0;
                net[id] = 0;
                netNear[id] = 0;
                netFar[id] = 0;
            }
            count = 0;
        }
    }

    private final int n;
    private final int[] defaultLine;
    /** Legal entity of each account; a line may be used by any account of its giver's entity. */
    private final int[] entity;
    private final long[] hasOverride;
    private final LongIntMap overrides = new LongIntMap(1024, NOT_OVERRIDDEN);
    private CreditLine[] lines = new CreditLine[64];

    private final InstrumentType[] instrumentType;
    private final int[] baseCurrency;
    private final int[] nearDate;
    private final int[] farDate;
    private final Map<String, Integer> currencyIndex = new HashMap<>();
    private final long[] usdRateE8;

    private int businessDate = NO_DATE;
    private long version;
    /** Bumped by anything that can change headroom: trades, rates, dates, lines, relationships. */
    private long usageVersion;
    private boolean simulating;
    private final Overlay ownOverlay = new Overlay();

    /** Accounts are numbered 1..maxAccounts. */
    public CreditEngine(int maxAccounts, RefData refData) {
        if (maxAccounts < 1 || maxAccounts > 4096) {
            throw new IllegalArgumentException("maxAccounts must be 1..4096 (dense pair table)");
        }
        this.n = maxAccounts + 1;
        this.entity = new int[n];
        for (int account = 1; account < n; account++) {
            entity[account] = refData.entityOf(account);
        }
        this.defaultLine = new int[n * n];
        Arrays.fill(defaultLine, NO_LINE);
        this.hasOverride = new long[(n * n + 63) / 64];
        int cap = refData.instrumentCapacity();
        instrumentType = new InstrumentType[cap];
        baseCurrency = new int[cap];
        nearDate = new int[cap];
        farDate = new int[cap];
        Arrays.fill(nearDate, -1);
        Arrays.fill(farDate, -1);
        for (Instrument i : refData.instruments()) {
            instrumentType[i.id()] = i.type();
            baseCurrency[i.id()] = currencyIndex.computeIfAbsent(i.baseCurrency(), k -> currencyIndex.size());
        }
        usdRateE8 = new long[Math.max(1, currencyIndex.size())];
        Integer usd = currencyIndex.get("USD");
        if (usd != null) {
            usdRateE8[usd] = RATE_SCALE; // USD per USD is 1 by definition, so it can never be missing
        }
    }

    // ------------------------------------------------------------------ admin (sequenced inputs)

    /**
     * Creates or modifies a line extended by {@code giver}. Reducing the limit below usage blocks
     * further exposure-increasing trades. The giver's legal entity cannot change, and a line cannot
     * switch between DSL and NOP while it has open usage, because each records only its own measure.
     */
    public CreditRefusal setLine(int lineId, int giver, CreditType type, long limitUsd) {
        if (type == null) {
            return CreditRefusal.NULL_FIELD;
        }
        if (lineId < 0 || lineId > MAX_LINE_ID) {
            return CreditRefusal.INVALID_LINE_ID;
        }
        if (!validAccount(giver)) {
            return CreditRefusal.UNKNOWN_ACCOUNT;
        }
        if (type != CreditType.UNLIMITED && (limitUsd < 0 || limitUsd > MAX_LIMIT_USD)) {
            return CreditRefusal.INVALID_LIMIT;
        }
        CreditLine line = lineId < lines.length ? lines[lineId] : null;
        if (line != null) {
            if (entity[line.giver] != entity[giver]) {
                return CreditRefusal.LINE_OWNED_BY_ANOTHER_ENTITY;
            }
            if (line.type != type && line.type != CreditType.UNLIMITED && type != CreditType.UNLIMITED && line.size > 0) {
                return CreditRefusal.LINE_TYPE_CHANGE_WITH_OPEN_USAGE;
            }
        }
        if (lineId >= lines.length) {
            lines = Arrays.copyOf(lines, Math.max(lineId + 1, lines.length * 2));
        }
        if (line == null) {
            line = new CreditLine();
            line.giver = giver;
            line.type = type;
            lines[lineId] = line;
        }
        if (line.type == CreditType.UNLIMITED && type != CreditType.UNLIMITED) {
            line.clearUsage(); // usage is not tracked while unlimited
        }
        line.type = type;
        line.limitE8 = type == CreditType.UNLIMITED ? 0 : limitUsd * RATE_SCALE;
        version++;
        usageVersion++;
        return null;
    }

    /** Bumped by anything that can change headroom. Market data screening re-evaluates when it moves. */
    public long usageVersion() {
        return usageVersion;
    }

    /** Bumped when lines or relationships change (eligibility may have changed). Not bumped by trades. */
    public long version() {
        return version;
    }

    /**
     * Maps the credit {@code from} extends to {@code to} onto a line, for one instrument or for
     * {@link #ALL_INSTRUMENTS}. {@code lineId} {@link #NO_LINE} means no credit.
     */
    public CreditRefusal setRelationship(int from, int to, int instrumentId, int lineId) {
        if (!validAccount(from) || !validAccount(to)) {
            return CreditRefusal.UNKNOWN_ACCOUNT;
        }
        if (lineId != NO_LINE && (lineId < 0 || lineId >= lines.length || lines[lineId] == null)) {
            return CreditRefusal.UNKNOWN_LINE;
        }
        if (lineId != NO_LINE && entity[lines[lineId].giver] != entity[from]) {
            return CreditRefusal.LINE_OWNED_BY_ANOTHER_ENTITY;
        }
        if (instrumentId != ALL_INSTRUMENTS && !validInstrument(instrumentId)) {
            return CreditRefusal.UNKNOWN_INSTRUMENT;
        }
        int pair = from * n + to;
        version++;
        usageVersion++;
        if (instrumentId == ALL_INSTRUMENTS) {
            defaultLine[pair] = lineId;
        } else {
            overrides.put(overrideKey(pair, instrumentId), lineId);
            hasOverride[pair >>> 6] |= 1L << (pair & 63);
        }
        return null;
    }

    public CreditRefusal setUsdRate(String currency, long rateE8) {
        Integer idx = currency == null ? null : currencyIndex.get(currency);
        if (idx == null) {
            return CreditRefusal.UNKNOWN_CURRENCY;
        }
        if (rateE8 <= 0 || rateE8 > MAX_RATE_E8) {
            return CreditRefusal.INVALID_RATE;
        }
        usdRateE8[idx] = rateE8;
        usageVersion++;
        return null;
    }

    /** Value dates as epoch days. {@code far} is required for SWAPs and ignored otherwise. */
    public CreditRefusal setValueDates(int instrumentId, int near, int far) {
        if (!validInstrument(instrumentId)) {
            return CreditRefusal.UNKNOWN_INSTRUMENT;
        }
        boolean swap = instrumentType[instrumentId] == InstrumentType.SWAP;
        if (near < 0 || swap && far < near) {
            return CreditRefusal.INVALID_VALUE_DATES;
        }
        if (near < businessDate) {
            return CreditRefusal.VALUE_DATE_BEFORE_BUSINESS_DATE;
        }
        nearDate[instrumentId] = near;
        farDate[instrumentId] = swap ? far : -1;
        usageVersion++;
        return null;
    }

    /** Sets the business date and releases usage for every value date before it. */
    public CreditRefusal rollValueDate(int today, BreachListener breaches) {
        if (today < 0) {
            return CreditRefusal.INVALID_VALUE_DATES;
        }
        if (today < businessDate) {
            return CreditRefusal.BUSINESS_DATE_WENT_BACKWARDS;
        }
        businessDate = today;
        usageVersion++;
        for (int id = 0; id < lines.length; id++) {
            CreditLine line = lines[id];
            if (line == null) {
                continue;
            }
            long before = line.netTotal;
            line.roll(today);
            if (line.type == CreditType.NOP && line.netTotal != before && Math.abs(line.netTotal) > line.limitE8) {
                breaches.onBreach(id, line.netTotal, line.limitE8);
            }
        }
        return null;
    }

    /** A NOP line went over its limit with no trade (a SWAP's near leg settled, leaving the far leg). */
    @FunctionalInterface
    public interface BreachListener {
        void onBreach(int lineId, long usageE8, long limitE8);
    }

    /** The USD rate and every value date this instrument's credit checks need are present and current. */
    public boolean hasRequiredData(int instrumentId) {
        return usdRateE8[baseCurrency[instrumentId]] > 0 && datesCurrent(instrumentId);
    }

    private boolean datesCurrent(int instrumentId) {
        int near = nearDate[instrumentId];
        return near >= 0 && near >= businessDate
                && (instrumentType[instrumentId] != InstrumentType.SWAP || farDate[instrumentId] >= near);
    }

    // ------------------------------------------------------------------ matching (hot path)

    public int lineFor(int from, int to, int instrumentId) {
        int pair = from * n + to;
        if ((hasOverride[pair >>> 6] & (1L << (pair & 63))) != 0) {
            int v = overrides.get(overrideKey(pair, instrumentId));
            if (v != NOT_OVERRIDDEN) {
                return v;
            }
        }
        return defaultLine[pair];
    }

    /** Both directions have a line for the instrument. Says nothing about remaining headroom. */
    public boolean eligible(int a, int b, int instrumentId) {
        return lineFor(a, b, instrumentId) != NO_LINE && lineFor(b, a, instrumentId) != NO_LINE;
    }

    /** True if either direction between these accounts is limited, so the quantity they can trade depends on usage. */
    public boolean limited(int a, int b, int instrumentId) {
        int ab = lineFor(a, b, instrumentId);
        int ba = lineFor(b, a, instrumentId);
        return ab != NO_LINE && ba != NO_LINE
                && (lines[ab].type != CreditType.UNLIMITED || lines[ba].type != CreditType.UNLIMITED);
    }

    /**
     * Largest base quantity the two accounts could trade now, with {@code aggressor} on
     * {@code aggressorSide}, without breaching either direction's line. {@link #UNLIMITED_CAPACITY}
     * if nothing limits it; 0 if they are not eligible.
     */
    public long capacity(int aggressor, int passive, int instrumentId, Side aggressorSide) {
        return capacity(aggressor, passive, instrumentId, aggressorSide, simulating ? ownOverlay : null);
    }

    /** As {@link #capacity(int, int, int, Side)}, counting {@code overlay}'s dry-run usage (null for none). */
    public long capacity(int aggressor, int passive, int instrumentId, Side aggressorSide, Overlay overlay) {
        int toPassive = lineFor(aggressor, passive, instrumentId);
        int toAggressor = lineFor(passive, aggressor, instrumentId);
        if (toPassive == NO_LINE || toAggressor == NO_LINE) {
            return 0;
        }
        int sign = aggressorSide == Side.BUY ? 1 : -1;
        long a = lineCapacity(toPassive, instrumentId, sign, overlay);
        if (a == 0) {
            return 0;
        }
        return Math.min(a, lineCapacity(toAggressor, instrumentId, -sign, overlay));
    }

    /** Records a fill. The caller must not exceed {@link #capacity}. */
    public void commit(int aggressor, int passive, int instrumentId, Side aggressorSide, long qty) {
        int sign = aggressorSide == Side.BUY ? 1 : -1;
        if (simulating) {
            commitTo(ownOverlay, aggressor, passive, instrumentId, sign, qty);
            return;
        }
        if (qty > capacity(aggressor, passive, instrumentId, aggressorSide, null)) {
            throw new IllegalStateException("Fill of " + qty + " exceeds credit capacity"); // before either line changes
        }
        usageVersion++;
        commitLine(lineFor(aggressor, passive, instrumentId), instrumentId, sign, qty);
        commitLine(lineFor(passive, aggressor, instrumentId), instrumentId, -sign, qty);
    }

    /** Records a dry-run fill in {@code overlay}. The real accounting is not touched. */
    public void commitOverlay(Overlay overlay, int aggressor, int passive, int instrumentId, Side aggressorSide, long qty) {
        commitTo(overlay, aggressor, passive, instrumentId, aggressorSide == Side.BUY ? 1 : -1, qty);
    }

    /** Sizes an overlay for this engine's lines. Call on the engine's thread before handing it to a reader. */
    public void prepare(Overlay overlay) {
        overlay.ensure(lines.length);
    }

    private void commitTo(Overlay overlay, int aggressor, int passive, int instrumentId, int sign, long qty) {
        overlayLine(overlay, lineFor(aggressor, passive, instrumentId), instrumentId, sign, qty);
        overlayLine(overlay, lineFor(passive, aggressor, instrumentId), instrumentId, -sign, qty);
    }

    private long lineCapacity(int lineId, int instrumentId, int giverSign, Overlay overlay) {
        CreditLine line = lines[lineId];
        if (line.type == CreditType.UNLIMITED) {
            return UNLIMITED_CAPACITY;
        }
        long rate = usdRateE8[baseCurrency[instrumentId]];
        if (rate <= 0) {
            return 0; // no USD rate yet: limited lines cannot be evaluated, so block
        }
        if (!datesCurrent(instrumentId)) {
            return 0; // missing or stale value date: limited lines cannot be evaluated, so block
        }
        boolean swap = instrumentType[instrumentId] == InstrumentType.SWAP;
        int near = nearDate[instrumentId];
        int far = farDate[instrumentId];
        if (line.type == CreditType.DSL) {
            if (!swap) {
                return headroom(line.limitE8 - grossUsed(line, lineId, near, instrumentId, overlay), rate);
            }
            if (near == far) {
                return headroom(line.limitE8 - grossUsed(line, lineId, near, instrumentId, overlay), 2 * rate);
            }
            return Math.min(headroom(line.limitE8 - grossUsed(line, lineId, near, instrumentId, overlay), rate),
                    headroom(line.limitE8 - grossUsed(line, lineId, far, instrumentId, overlay), rate));
        }
        if (!swap) {
            long net = overlay != null && overlay.used[lineId] ? line.netTotal + overlay.net[lineId] : line.netTotal;
            long room = giverSign > 0 ? line.limitE8 - net : line.limitE8 + net;
            return Math.min(headroom(room, rate), headroom(bucketRoom(line, lineId, near, instrumentId, giverSign, overlay), rate));
        }
        // A SWAP's legs net to zero, so only the per-date buckets bound it.
        if (near == far) {
            return MAX_BUCKET_E8 / rate;
        }
        return Math.min(headroom(bucketRoom(line, lineId, near, instrumentId, -giverSign, overlay), rate),
                headroom(bucketRoom(line, lineId, far, instrumentId, giverSign, overlay), rate));
    }

    private long bucketRoom(CreditLine line, int lineId, int date, int instrumentId, int sign, Overlay overlay) {
        long net = line.netAt(date);
        if (overlay != null && overlay.used[lineId]) {
            net += date == nearDate[instrumentId] ? overlay.netNear[lineId] : overlay.netFar[lineId];
        }
        return sign > 0 ? MAX_BUCKET_E8 - net : MAX_BUCKET_E8 + net;
    }

    private static long headroom(long roomE8, long perUnitE8) {
        return roomE8 <= 0 ? 0 : roomE8 / perUnitE8;
    }

    private void commitLine(int lineId, int instrumentId, int giverSign, long qty) {
        CreditLine line = lines[lineId];
        if (line.type == CreditType.UNLIMITED) {
            return;
        }
        long amount = qty * usdRateE8[baseCurrency[instrumentId]]; // bounded: qty <= capacity
        boolean dsl = line.type == CreditType.DSL;
        long gross = dsl ? amount : 0;
        long signed = dsl ? 0 : giverSign * amount;
        if (instrumentType[instrumentId] == InstrumentType.SWAP) {
            line.add(nearDate[instrumentId], gross, -signed);
            line.add(farDate[instrumentId], gross, signed);
        } else {
            line.add(nearDate[instrumentId], gross, signed);
        }
    }

    private void overlayLine(Overlay overlay, int lineId, int instrumentId, int giverSign, long qty) {
        CreditLine line = lines[lineId];
        if (line.type == CreditType.UNLIMITED) {
            return;
        }
        long amount = qty * usdRateE8[baseCurrency[instrumentId]];
        boolean dsl = line.type == CreditType.DSL;
        long gross = dsl ? amount : 0;
        long signed = dsl ? 0 : giverSign * amount;
        overlay.touch(lineId);
        overlay.grossNear[lineId] += gross;
        if (instrumentType[instrumentId] == InstrumentType.SWAP) {
            overlay.grossFar[lineId] += gross;
            overlay.netNear[lineId] -= signed;
            overlay.netFar[lineId] += signed;
        } else {
            overlay.net[lineId] += signed;
            overlay.netNear[lineId] += signed;
        }
    }

    // ------------------------------------------------------------------ dry runs

    /**
     * Starts a dry run: until {@link #endSimulation()}, {@link #commit} records into a temporary
     * overlay that {@link #capacity} includes. A dry run must cover one instrument only.
     */
    public void beginSimulation() {
        ownOverlay.ensure(lines.length);
        simulating = true;
    }

    public void endSimulation() {
        ownOverlay.clear();
        simulating = false;
    }

    private long grossUsed(CreditLine line, int lineId, int date, int instrumentId, Overlay overlay) {
        long used = line.grossAt(date);
        if (overlay != null && overlay.used[lineId]) {
            if (date == nearDate[instrumentId]) {
                used += overlay.grossNear[lineId];
            }
            if (date == farDate[instrumentId]) {
                used += overlay.grossFar[lineId];
            }
        }
        return used;
    }

    // ------------------------------------------------------------------ snapshots

    /** Writes the complete credit state. */
    public void save(DataOutput out) throws IOException {
        out.writeInt(businessDate);
        out.writeLong(version);
        out.writeLong(usageVersion);
        for (long rate : usdRateE8) {
            out.writeLong(rate);
        }
        for (int i = 0; i < nearDate.length; i++) {
            out.writeInt(nearDate[i]);
            out.writeInt(farDate[i]);
        }
        for (int line : defaultLine) {
            out.writeInt(line);
        }
        for (long word : hasOverride) {
            out.writeLong(word);
        }
        out.writeInt(overrides.size());
        overrides.forEach((key, value) -> {
            out.writeLong(key);
            out.writeInt(value);
        });
        out.writeInt(lineCount());
        for (int id = 0; id < lines.length; id++) {
            CreditLine l = lines[id];
            if (l == null) {
                continue;
            }
            out.writeInt(id);
            out.writeInt(l.giver);
            out.writeUTF(l.type.name());
            out.writeLong(l.limitE8);
            out.writeLong(l.netTotal);
            out.writeInt(l.size);
            for (int i = 0; i < l.size; i++) {
                out.writeInt(l.dates[i]);
                out.writeLong(l.gross[i]);
                out.writeLong(l.net[i]);
            }
        }
    }

    /** Replaces this engine's state with a saved one (same accounts and reference data). */
    public void restore(DataInput in) throws IOException {
        businessDate = in.readInt();
        version = in.readLong();
        usageVersion = in.readLong();
        for (int i = 0; i < usdRateE8.length; i++) {
            usdRateE8[i] = in.readLong();
        }
        for (int i = 0; i < nearDate.length; i++) {
            nearDate[i] = in.readInt();
            farDate[i] = in.readInt();
        }
        for (int i = 0; i < defaultLine.length; i++) {
            defaultLine[i] = in.readInt();
        }
        for (int i = 0; i < hasOverride.length; i++) {
            hasOverride[i] = in.readLong();
        }
        overrides.clear();
        int entries = in.readInt();
        for (int i = 0; i < entries; i++) {
            long key = in.readLong();
            overrides.put(key, in.readInt());
        }
        lines = new CreditLine[lines.length];
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            if (id >= lines.length) {
                lines = Arrays.copyOf(lines, Math.max(id + 1, lines.length * 2));
            }
            CreditLine l = new CreditLine();
            l.giver = in.readInt();
            l.type = CreditType.valueOf(in.readUTF());
            l.limitE8 = in.readLong();
            l.netTotal = in.readLong();
            l.size = in.readInt();
            int capacity = Math.max(4, l.size);
            l.dates = new int[capacity];
            l.gross = new long[capacity];
            l.net = new long[capacity];
            for (int k = 0; k < l.size; k++) {
                l.dates[k] = in.readInt();
                l.gross[k] = in.readLong();
                l.net[k] = in.readLong();
            }
            lines[id] = l;
        }
    }

    // ------------------------------------------------------------------ queries

    /** True if the two accounts belong to the same legal entity (self-trade prevention). */
    public boolean sameEntity(int a, int b) {
        return entity[a] == entity[b];
    }

    public CreditType lineType(int lineId) {
        return lines[lineId].type;
    }

    public long limitUsd(int lineId) {
        return lines[lineId].limitE8 / RATE_SCALE;
    }

    /** Gross DSL usage on a value date, in USD × {@value #RATE_SCALE}. */
    public long dslUsageE8(int lineId, int date) {
        return lines[lineId].grossAt(date);
    }

    /** Signed net position of the line's giver, in USD × {@value #RATE_SCALE}. */
    public long nopE8(int lineId) {
        return lines[lineId].netTotal;
    }

    public int lineCount() {
        int c = 0;
        for (CreditLine l : lines) {
            if (l != null) {
                c++;
            }
        }
        return c;
    }

    /** True if no DSL or NOP line is over its limit. A diagnostic for tests. */
    public boolean withinLimits() {
        for (CreditLine l : lines) {
            if (l == null || l.type == CreditType.UNLIMITED) {
                continue;
            }
            if (l.type == CreditType.NOP && Math.abs(l.netTotal) > l.limitE8) {
                return false;
            }
            if (l.type == CreditType.DSL) {
                for (int i = 0; i < l.size; i++) {
                    if (l.gross[i] > l.limitE8) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean validAccount(int account) {
        return account >= 1 && account < n;
    }

    private boolean validInstrument(int instrumentId) {
        return instrumentId >= 0 && instrumentId < instrumentType.length && instrumentType[instrumentId] != null;
    }

    private static long overrideKey(int pair, int instrumentId) {
        return ((long) pair << 20) | instrumentId;
    }
}
