package clob.refdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.collections.LongHashSet;

/** Immutable reference data: instruments (array-indexed by id) and user-to-account entitlements. */
public final class RefData {
    private final Instrument[] byId;
    private final List<Instrument> instruments;
    private final Map<String, Instrument> byKey;
    private final LongHashSet entitlements;

    private RefData(Builder b) {
        int maxId = -1;
        for (Instrument i : b.instruments) {
            maxId = Math.max(maxId, i.id());
        }
        byId = new Instrument[maxId + 1];
        byKey = new HashMap<>();
        for (Instrument i : b.instruments) {
            byId[i.id()] = i;
            byKey.put(i.key(), i);
        }
        instruments = Collections.unmodifiableList(new ArrayList<>(b.instruments));
        entitlements = new LongHashSet();
        entitlements.addAll(b.entitlements);
    }

    public Instrument instrument(int id) {
        return id >= 0 && id < byId.length ? byId[id] : null;
    }

    public Instrument instrument(String symbol, String tenor) {
        return byKey.get(symbol + " " + tenor);
    }

    public List<Instrument> instruments() {
        return instruments;
    }

    /** One more than the largest instrument id. */
    public int instrumentCapacity() {
        return byId.length;
    }

    public boolean isEntitled(int userId, int accountId) {
        return entitlements.contains(entitlementKey(userId, accountId));
    }

    private static long entitlementKey(int userId, int accountId) {
        return ((long) userId << 32) | (accountId & 0xFFFF_FFFFL);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Instrument> instruments = new ArrayList<>();
        private final Map<Integer, Instrument> ids = new HashMap<>();
        private final Map<String, Instrument> keys = new HashMap<>();
        private final LongHashSet entitlements = new LongHashSet();

        public Builder add(Instrument instrument) {
            if (ids.putIfAbsent(instrument.id(), instrument) != null) {
                throw new IllegalArgumentException("Duplicate instrument id " + instrument.id());
            }
            if (keys.putIfAbsent(instrument.key(), instrument) != null) {
                throw new IllegalArgumentException("Duplicate instrument " + instrument.key());
            }
            instruments.add(instrument);
            return this;
        }

        /** Maps a user to an account (many-to-many). */
        public Builder entitle(int userId, int accountId) {
            entitlements.add(entitlementKey(userId, accountId));
            return this;
        }

        public RefData build() {
            return new RefData(this);
        }
    }
}
