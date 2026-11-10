package clob.sequencer;

/**
 * Stable wire ids for one enum. The id of a constant is its position in the table given here, which
 * is independent of the enum's declaration order, so reordering an enum cannot reinterpret history.
 * New constants are appended to the end of the table; existing entries never move.
 */
public final class WireIds<E extends Enum<E>> {
    private final E[] table;
    private final byte[] idOf;

    @SafeVarargs
    @SuppressWarnings("varargs")
    WireIds(E... table) {
        Class<E> type = table[0].getDeclaringClass();
        E[] constants = type.getEnumConstants();
        if (table.length != constants.length) {
            throw new IllegalStateException(type.getSimpleName() + " has constants without a wire id");
        }
        this.table = table.clone();
        this.idOf = new byte[constants.length];
        java.util.Arrays.fill(idOf, (byte) -1);
        for (int id = 0; id < table.length; id++) {
            if (idOf[table[id].ordinal()] != -1) {
                throw new IllegalStateException("Duplicate wire id for " + table[id]);
            }
            idOf[table[id].ordinal()] = (byte) id;
        }
    }

    /** The constant's wire id, or -1 for null. */
    public byte id(E e) {
        return e == null ? -1 : idOf[e.ordinal()];
    }

    /** The constant with this wire id, or null for -1 or an id this version does not know. */
    public E of(int id) {
        return id < 0 || id >= table.length ? null : table[id];
    }
}
