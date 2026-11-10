package clob.sequencer;

import clob.common.Side;
import clob.credit.CreditType;
import clob.engine.CancelReason;
import clob.engine.CancelSource;
import clob.engine.MassCancelScope;
import clob.engine.RejectReason;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import clob.engine.SessionState;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.TimeInForce;

/** The wire ids of every enum in journalled and output messages. Part of the journal schema. */
public final class Wire {
    private Wire() {}

    /** Bumped on any incompatible change to the input or output layout. Checked before replay. */
    public static final int SCHEMA_VERSION = 2;

    public static final WireIds<Side> SIDES = new WireIds<>(Side.BUY, Side.SELL);
    public static final WireIds<OrderType> ORDER_TYPES = new WireIds<>(OrderType.LIMIT, OrderType.MARKET,
            OrderType.ICEBERG, OrderType.PEGGED, OrderType.MID, OrderType.FIXING);
    public static final WireIds<TimeInForce> TIFS = new WireIds<>(TimeInForce.DAY, TimeInForce.GTC, TimeInForce.GTT,
            TimeInForce.FOK, TimeInForce.IOC);
    public static final WireIds<PegReference> PEG_REFERENCES = new WireIds<>(PegReference.MID, PegReference.NEAR_TOUCH);
    public static final WireIds<CancelSource> CANCEL_SOURCES = new WireIds<>(CancelSource.PARTICIPANT,
            CancelSource.DISCONNECT, CancelSource.ADMIN);
    public static final WireIds<MassCancelScope> SCOPES = new WireIds<>(MassCancelScope.ACCOUNT, MassCancelScope.USER);
    public static final WireIds<CreditType> CREDIT_TYPES = new WireIds<>(CreditType.UNLIMITED, CreditType.DSL,
            CreditType.NOP);
    public static final WireIds<SessionState> SESSION_STATES = new WireIds<>(SessionState.PRE_OPEN, SessionState.OPEN,
            SessionState.HALTED, SessionState.CLOSED);
    public static final WireIds<RequestType> REQUEST_TYPES = new WireIds<>(RequestType.CANCEL, RequestType.REPLACE);
    public static final WireIds<CancelReason> CANCEL_REASONS = new WireIds<>(CancelReason.PARTICIPANT,
            CancelReason.DISCONNECT, CancelReason.ADMIN, CancelReason.IOC_UNFILLED, CancelReason.FOK_UNFILLED,
            CancelReason.MARKET_UNFILLED, CancelReason.RESIDUAL_BELOW_MIN_QTY, CancelReason.EXPIRED,
            CancelReason.END_OF_DAY, CancelReason.FIXING_CUTOFF, CancelReason.SELF_TRADE, CancelReason.HALTED);
    public static final WireIds<RejectReason> REJECT_REASONS = new WireIds<>(RejectReason.UNKNOWN_INSTRUMENT,
            RejectReason.MISSING_FIELD, RejectReason.NOT_ENTITLED, RejectReason.DUPLICATE_ORDER_ID,
            RejectReason.UNSUPPORTED_ORDER_TYPE, RejectReason.UNSUPPORTED_TIF, RejectReason.INVALID_QUANTITY,
            RejectReason.INVALID_PRICE, RejectReason.INVALID_SHOW, RejectReason.INVALID_EXPIRE_TIME,
            RejectReason.CAPACITY_EXCEEDED, RejectReason.PEG_REFERENCE_UNAVAILABLE, RejectReason.UNKNOWN_FIXING_EVENT,
            RejectReason.FIXING_NOT_OPEN, RejectReason.INVALID_TIF_FOR_TYPE, RejectReason.MARKET_NOT_OPEN,
            RejectReason.MARKET_HALTED, RejectReason.ORDER_TYPE_NOT_ALLOWED_IN_PRE_OPEN);
    public static final WireIds<RequestRejectReason> REQUEST_REJECT_REASONS = new WireIds<>(
            RequestRejectReason.UNKNOWN_ORDER, RequestRejectReason.NOT_ENTITLED, RequestRejectReason.INSIDE_MIN_TTL,
            RequestRejectReason.CANCEL_ALREADY_PENDING, RequestRejectReason.TOO_LATE, RequestRejectReason.SUPERSEDED,
            RequestRejectReason.INVALID_PRICE, RequestRejectReason.INVALID_QUANTITY, RequestRejectReason.INVALID_SHOW,
            RequestRejectReason.QTY_NOT_ABOVE_FILLED, RequestRejectReason.LEAVES_BELOW_MIN_QTY,
            RequestRejectReason.NOT_REPLACEABLE, RequestRejectReason.PENDING_IN_RACE, RequestRejectReason.MARKET_NOT_OPEN);
}
