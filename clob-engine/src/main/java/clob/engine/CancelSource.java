package clob.engine;

/** Who asked for a cancel. Disconnect and admin cancels bypass min TTL (confirmed, PLAN Q14a). */
public enum CancelSource {
    PARTICIPANT,
    DISCONNECT,
    ADMIN;

    CancelReason reason() {
        return switch (this) {
            case PARTICIPANT -> CancelReason.PARTICIPANT;
            case DISCONNECT -> CancelReason.DISCONNECT;
            case ADMIN -> CancelReason.ADMIN;
        };
    }
}
