package clob.credit;

import clob.common.Side;
import clob.refdata.Instrument;
import clob.refdata.InstrumentType;
import clob.refdata.RefData;
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
 * USD and rates at {@value #MAX_RATE_E8} (10 USD per unit) so that the products fit in a long.
 */
public final class CreditEngine {
    public static final int NO_LINE = -1;
    /** Relationship key for "every instrument without an override". */
    public static final int ALL_INSTRUMENTS = -1;
    public static final long RATE_SCALE = 100_000_000L;
    public static final long MAX_LIMIT_USD = 10_000_000_000L;
    public static final long MAX_RATE_E8 = 1_000_000_000L;
    public static final long UNLIMITED_CAPACITY = Long.MAX_VALUE;

    private static final int NOT_OVERRIDDEN = -2;

    private final int n;
    private final int[] defaultLine;
    private final long[] hasOverride;
    private final LongIntMap overrides = new LongIntMap(1024, NOT_OVERRIDDEN);
    private CreditLine[] lines = new CreditLine[64];

    private final InstrumentType[] instrumentType;
    private final int[] baseCurrency;
    private final int[] nearDate;
    private final int[] farDate;
    private final Map<String, Integer> currencyIndex = new HashMap<>();
    private final long[] usdRateE8;

    private long version;
    private boolean simulating;
    private CreditLine[] shadowed = new CreditLine[16];
    private int shadowedCount;

    /** Accounts are numbered 1..maxAccounts. */
    public CreditEngine(int maxAccounts, RefData refData) {
        if (maxAccounts < 1 || maxAccounts > 4096) {
            throw new IllegalArgumentException("maxAccounts must be 1..4096 (dense pair table)");
        }
        this.n = maxAccounts + 1;
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
    }

    // ------------------------------------------------------------------ admin (sequenced inputs)

    /**
     * Creates or modifies a line extended by {@code giver}. Reducing the limit below usage blocks
     * further exposure-increasing trades. The giver of an existing line cannot change.
     */
    public void setLine(int lineId, int giver, CreditType type, long limitUsd) {
        if (lineId < 0) {
            throw new IllegalArgumentException("lineId must be >= 0");
        }
        checkAccount(giver);
        if (type != CreditType.UNLIMITED && (limitUsd < 0 || limitUsd > MAX_LIMIT_USD)) {
            throw new IllegalArgumentException("limitUsd must be 0.." + MAX_LIMIT_USD);
        }
        if (lineId >= lines.length) {
            lines = Arrays.copyOf(lines, Math.max(lineId + 1, lines.length * 2));
        }
        CreditLine line = lines[lineId];
        if (line == null) {
            line = new CreditLine();
            line.giver = giver;
            line.type = type;
            lines[lineId] = line;
        } else if (line.giver != giver) {
            throw new IllegalArgumentException("Line " + lineId + " belongs to account " + line.giver);
        }
        if (line.type == CreditType.UNLIMITED && type != CreditType.UNLIMITED) {
            line.clearUsage(); // usage is not tracked while unlimited
        }
        line.type = type;
        line.limitE8 = type == CreditType.UNLIMITED ? 0 : limitUsd * RATE_SCALE;
        version++;
    }

    /** Bumped when lines or relationships change (eligibility may have changed). Not bumped by trades. */
    public long version() {
        return version;
    }

    /**
     * Maps the credit {@code from} extends to {@code to} onto a line, for one instrument or for
     * {@link #ALL_INSTRUMENTS}. {@code lineId} {@link #NO_LINE} means no credit.
     */
    public void setRelationship(int from, int to, int instrumentId, int lineId) {
        checkAccount(from);
        checkAccount(to);
        if (lineId != NO_LINE && (lineId < 0 || lineId >= lines.length || lines[lineId] == null)) {
            throw new IllegalArgumentException("Unknown line " + lineId);
        }
        if (lineId != NO_LINE && lines[lineId].giver != from) {
            throw new IllegalArgumentException("Line " + lineId + " is extended by account " + lines[lineId].giver
                    + ", not " + from);
        }
        int pair = from * n + to;
        version++;
        if (instrumentId == ALL_INSTRUMENTS) {
            defaultLine[pair] = lineId;
        } else {
            if (instrumentId < 0 || instrumentId >= instrumentType.length || instrumentType[instrumentId] == null) {
                throw new IllegalArgumentException("Unknown instrument " + instrumentId);
            }
            overrides.put(overrideKey(pair, instrumentId), lineId);
            hasOverride[pair >>> 6] |= 1L << (pair & 63);
        }
    }

    public void setUsdRate(String currency, long rateE8) {
        Integer idx = currencyIndex.get(currency);
        if (idx == null) {
            throw new IllegalArgumentException("No instrument has base currency " + currency);
        }
        if (rateE8 <= 0 || rateE8 > MAX_RATE_E8) {
            throw new IllegalArgumentException("rateE8 must be 1.." + MAX_RATE_E8);
        }
        usdRateE8[idx] = rateE8;
    }

    /** Value dates as epoch days. {@code farDate} is only used for SWAPs (pass -1 otherwise). */
    public void setValueDates(int instrumentId, int near, int far) {
        nearDate[instrumentId] = near;
        farDate[instrumentId] = instrumentType[instrumentId] == InstrumentType.SWAP ? far : -1;
    }

    /** Releases usage for every value date before {@code today}. */
    public void rollValueDate(int today) {
        for (CreditLine line : lines) {
            if (line != null) {
                line.roll(today);
            }
        }
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

    /**
     * Largest base quantity the two accounts could trade now, with {@code aggressor} on
     * {@code aggressorSide}, without breaching either direction's line. {@link #UNLIMITED_CAPACITY}
     * if nothing limits it; 0 if they are not eligible.
     */
    public long capacity(int aggressor, int passive, int instrumentId, Side aggressorSide) {
        int toPassive = lineFor(aggressor, passive, instrumentId);
        int toAggressor = lineFor(passive, aggressor, instrumentId);
        if (toPassive == NO_LINE || toAggressor == NO_LINE) {
            return 0;
        }
        int sign = aggressorSide == Side.BUY ? 1 : -1;
        long a = lineCapacity(toPassive, instrumentId, sign);
        if (a == 0) {
            return 0;
        }
        return Math.min(a, lineCapacity(toAggressor, instrumentId, -sign));
    }

    /** Records a fill. The caller must not exceed {@link #capacity}. */
    public void commit(int aggressor, int passive, int instrumentId, Side aggressorSide, long qty) {
        int sign = aggressorSide == Side.BUY ? 1 : -1;
        commitLine(lineFor(aggressor, passive, instrumentId), instrumentId, sign, qty);
        commitLine(lineFor(passive, aggressor, instrumentId), instrumentId, -sign, qty);
    }

    private long lineCapacity(int lineId, int instrumentId, int giverSign) {
        CreditLine line = lines[lineId];
        if (line.type == CreditType.UNLIMITED) {
            return UNLIMITED_CAPACITY;
        }
        long rate = usdRateE8[baseCurrency[instrumentId]];
        if (rate <= 0) {
            return 0; // no USD rate yet: limited lines cannot be evaluated, so block
        }
        boolean swap = instrumentType[instrumentId] == InstrumentType.SWAP;
        int near = nearDate[instrumentId];
        int far = farDate[instrumentId];
        if (line.type == CreditType.DSL) {
            if (near < 0 || swap && far < 0) {
                return 0;
            }
            if (!swap) {
                return headroom(line.limitE8 - grossUsed(line, near, instrumentId), rate);
            }
            if (near == far) {
                return headroom(line.limitE8 - grossUsed(line, near, instrumentId), 2 * rate);
            }
            return Math.min(headroom(line.limitE8 - grossUsed(line, near, instrumentId), rate),
                    headroom(line.limitE8 - grossUsed(line, far, instrumentId), rate));
        }
        // NOP: coefficient of qty in the giver's signed position.
        long coefficient = swap ? 0 : giverSign * rate;
        return nopCapacity(line, coefficient);
    }

    private static long nopCapacity(CreditLine line, long coefficient) {
        if (coefficient == 0) {
            return UNLIMITED_CAPACITY;
        }
        long net = netUsed(line);
        long room = coefficient > 0 ? line.limitE8 - net : line.limitE8 + net;
        return headroom(room, Math.abs(coefficient));
    }

    private static long headroom(long roomE8, long perUnitE8) {
        return roomE8 <= 0 ? 0 : roomE8 / perUnitE8;
    }

    private void commitLine(int lineId, int instrumentId, int giverSign, long qty) {
        CreditLine line = lines[lineId];
        if (line.type == CreditType.UNLIMITED) {
            return;
        }
        long amount = Math.multiplyExact(qty, usdRateE8[baseCurrency[instrumentId]]);
        if (simulating) {
            if (!line.shadowed) {
                line.shadowed = true;
                if (shadowedCount == shadowed.length) {
                    shadowed = Arrays.copyOf(shadowed, shadowedCount * 2);
                }
                shadowed[shadowedCount++] = line;
            }
            if (instrumentType[instrumentId] == InstrumentType.SWAP) {
                line.shadowNear += amount;
                line.shadowFar += amount;
            } else {
                line.shadowNear += amount;
                line.shadowNet += giverSign * amount;
            }
            return;
        }
        if (instrumentType[instrumentId] == InstrumentType.SWAP) {
            line.add(nearDate[instrumentId], amount, -giverSign * amount);
            line.add(farDate[instrumentId], amount, giverSign * amount);
        } else {
            line.add(nearDate[instrumentId], amount, giverSign * amount);
        }
    }

    // ------------------------------------------------------------------ dry runs

    /**
     * Starts a dry run: until {@link #endSimulation()}, {@link #commit} records into a temporary
     * overlay that {@link #capacity} includes. A dry run must cover one instrument only.
     */
    public void beginSimulation() {
        simulating = true;
    }

    public void endSimulation() {
        for (int i = 0; i < shadowedCount; i++) {
            shadowed[i].clearShadow();
            shadowed[i] = null;
        }
        shadowedCount = 0;
        simulating = false;
    }

    private long grossUsed(CreditLine line, int date, int instrumentId) {
        long used = line.grossAt(date);
        if (line.shadowed) {
            if (date == nearDate[instrumentId]) {
                used += line.shadowNear;
            }
            if (date == farDate[instrumentId]) {
                used += line.shadowFar;
            }
        }
        return used;
    }

    private static long netUsed(CreditLine line) {
        return line.shadowed ? line.netTotal + line.shadowNet : line.netTotal;
    }

    // ------------------------------------------------------------------ queries

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

    private void checkAccount(int account) {
        if (account < 1 || account >= n) {
            throw new IllegalArgumentException("account must be 1.." + (n - 1));
        }
    }

    private static long overrideKey(int pair, int instrumentId) {
        return ((long) pair << 20) | instrumentId;
    }
}
