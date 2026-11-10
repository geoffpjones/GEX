package clob.credit;

import java.util.Arrays;

/**
 * One credit pool: a type, a limit, and usage per value date. Every relationship (and instrument)
 * mapped to the same line shares its usage. Amounts are in USD × {@link CreditEngine#RATE_SCALE}.
 */
final class CreditLine {
    /** The account extending this credit; only its relationships may use the line. */
    int giver;
    CreditType type;
    long limitE8;

    /** Value dates (epoch days) with open usage; small, so scanned linearly. */
    int[] dates = new int[4];
    /** Gross settlement amount per date (DSL). */
    long[] gross = new long[4];
    /** Signed position per date, from the credit giver's side (+ = giver bought base). */
    long[] net = new long[4];
    int size;
    /** Sum of {@link #net} over all open dates (NOP). */
    long netTotal;

    int indexOf(int date) {
        for (int i = 0; i < size; i++) {
            if (dates[i] == date) {
                return i;
            }
        }
        return -1;
    }

    long grossAt(int date) {
        int i = indexOf(date);
        return i < 0 ? 0 : gross[i];
    }

    long netAt(int date) {
        int i = indexOf(date);
        return i < 0 ? 0 : net[i];
    }

    /** Applies a fill already proved to fit by {@link CreditEngine#capacity}. */
    void add(int date, long grossAmount, long signedAmount) {
        int i = indexOf(date);
        if (i < 0) {
            if (size == dates.length) {
                int n = size * 2;
                dates = Arrays.copyOf(dates, n);
                gross = Arrays.copyOf(gross, n);
                net = Arrays.copyOf(net, n);
            }
            i = size++;
            dates[i] = date;
            gross[i] = 0;
            net[i] = 0;
        }
        gross[i] += grossAmount;
        net[i] += signedAmount;
        netTotal += signedAmount;
    }

    /**
     * Releases usage for value dates before {@code today} (settled). A SWAP's far leg stays open once
     * its near leg settles, so the net can move here with no trade; it saturates at
     * {@link CreditEngine#MAX_BUCKET_E8}, which is far above any limit.
     */
    void roll(int today) {
        int w = 0;
        for (int i = 0; i < size; i++) {
            if (dates[i] < today) {
                netTotal = Math.max(-CreditEngine.MAX_BUCKET_E8,
                        Math.min(CreditEngine.MAX_BUCKET_E8, netTotal - net[i]));
            } else {
                dates[w] = dates[i];
                gross[w] = gross[i];
                net[w] = net[i];
                w++;
            }
        }
        size = w;
    }

    void clearUsage() {
        size = 0;
        netTotal = 0;
    }
}
