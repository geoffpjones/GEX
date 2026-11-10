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
    private final FixingEvent[] fixingEvents;
    private final Map<Integer, Integer> entities;

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
        entities = new HashMap<>(b.entities);
        int maxEvent = -1;
        for (FixingEvent e : b.fixingEvents) {
            maxEvent = Math.max(maxEvent, e.id());
        }
        fixingEvents = new FixingEvent[maxEvent + 1];
        for (FixingEvent e : b.fixingEvents) {
            fixingEvents[e.id()] = e;
        }
    }

    public FixingEvent fixingEvent(int id) {
        return id >= 0 && id < fixingEvents.length ? fixingEvents[id] : null;
    }

    /** One more than the largest fixing event id. */
    public int fixingEventCapacity() {
        return fixingEvents.length;
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

    /**
     * The account's legal entity. An account not assigned to one is its own entity, under an id that
     * cannot collide with a configured entity id.
     */
    public int entityOf(int accountId) {
        Integer entity = entities.get(accountId);
        return entity != null ? entity : -accountId;
    }

    /** Largest account id named by an entitlement or legal-entity assignment; 0 if none. */
    public int maxReferencedAccount() {
        int max = 0;
        for (long key : entitlements) {
            max = Math.max(max, (int) key);
        }
        for (int account : entities.keySet()) {
            max = Math.max(max, account);
        }
        return max;
    }

    /** Account to legal-entity assignments, for canonical comparison of reference data. */
    public Map<Integer, Integer> entityAssignments() {
        return Collections.unmodifiableMap(entities);
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
        private final List<FixingEvent> fixingEvents = new ArrayList<>();
        private final Map<Integer, FixingEvent> fixingIds = new HashMap<>();
        private final Map<Integer, Integer> entities = new HashMap<>();

        public Builder addFixingEvent(FixingEvent event) {
            if (fixingIds.putIfAbsent(event.id(), event) != null) {
                throw new IllegalArgumentException("Duplicate fixing event id " + event.id());
            }
            fixingEvents.add(event);
            return this;
        }

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

        /** Assigns an account to a legal entity ({@code entityId} must be positive). */
        public Builder entity(int accountId, int entityId) {
            if (accountId < 1 || entityId < 1) {
                throw new IllegalArgumentException("account and entity ids must be positive");
            }
            if (entities.putIfAbsent(accountId, entityId) != null) {
                throw new IllegalArgumentException("Account " + accountId + " already has a legal entity");
            }
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
