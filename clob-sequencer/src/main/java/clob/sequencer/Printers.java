package clob.sequencer;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.NewOrderCommand;
import clob.engine.RejectReason;
import clob.engine.ReplaceCommand;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;
import clob.engine.SessionState;
import org.agrona.DirectBuffer;

/** Human-readable renderings of inputs and outputs, for the audit tool and drop copy. Not hot path. */
public final class Printers {
    private Printers() {}

    public static String input(DirectBuffer b, int o) {
        long seq = InputCodec.sequence(b, o);
        long time = InputCodec.time(b, o);
        String body = switch (InputCodec.type(b, o)) {
            case InputCodec.NEW_ORDER -> {
                NewOrderCommand c = new NewOrderCommand();
                InputCodec.decodeNewOrder(b, o, c);
                yield "NEW_ORDER id=" + c.orderId + " instr=" + c.instrumentId + " acct=" + c.accountId + " user="
                        + c.userId + " " + c.side + " " + c.type + " " + c.tif + " px=" + c.price + " qty=" + c.qty
                        + (c.maxShow >= 0 ? " show=" + c.maxShow : "") + (c.pegReference != null ? " peg=" + c.pegReference : "")
                        + (c.discretion != 0 ? " disc=" + c.discretion : "") + (c.fixingEventId >= 0 ? " fix=" + c.fixingEventId : "")
                        + (c.expireTime != 0 ? " expire=" + c.expireTime : "");
            }
            case InputCodec.CANCEL -> "CANCEL id=" + InputCodec.cancelOrderId(b, o) + " user=" + InputCodec.cancelUserId(b, o)
                    + " source=" + InputCodec.cancelSource(b, o);
            case InputCodec.REPLACE -> {
                ReplaceCommand c = new ReplaceCommand();
                InputCodec.decodeReplace(b, o, c);
                yield "REPLACE id=" + c.orderId + " user=" + c.userId + " px=" + c.price + " qty=" + c.qty
                        + " show=" + c.maxShow + " disc=" + c.discretion;
            }
            case InputCodec.MASS_CANCEL -> "MASS_CANCEL scope=" + Wire.SCOPES.of(InputCodec.i8(b, o, 0))
                    + " id=" + InputCodec.i32(b, o, 1) + " user=" + InputCodec.i32(b, o, 5) + " source="
                    + Wire.CANCEL_SOURCES.of(InputCodec.i8(b, o, 9));
            case InputCodec.TIME_ADVANCE -> "TIME";
            case InputCodec.END_OF_DAY -> "END_OF_DAY";
            case InputCodec.CREDIT_LINE -> "CREDIT_LINE line=" + InputCodec.i32(b, o, 0) + " giver=" + InputCodec.i32(b, o, 4)
                    + " " + Wire.CREDIT_TYPES.of(InputCodec.i8(b, o, 8)) + " limitUsd=" + InputCodec.i64(b, o, 9);
            case InputCodec.CREDIT_RELATIONSHIP -> "CREDIT_RELATIONSHIP from=" + InputCodec.i32(b, o, 0) + " to="
                    + InputCodec.i32(b, o, 4) + " instr=" + InputCodec.i32(b, o, 8) + " line=" + InputCodec.i32(b, o, 12);
            case InputCodec.USD_RATE -> "USD_RATE " + InputCodec.usdRateCurrency(b, o) + " rateE8=" + InputCodec.i64(b, o, 8);
            case InputCodec.VALUE_DATES -> "VALUE_DATES instr=" + InputCodec.i32(b, o, 0) + " near=" + InputCodec.i32(b, o, 4)
                    + " far=" + InputCodec.i32(b, o, 8);
            case InputCodec.VALUE_DATE_ROLL -> "VALUE_DATE_ROLL today=" + InputCodec.i32(b, o, 0);
            case InputCodec.FIXING_SCHEDULE -> "FIXING_SCHEDULE event=" + InputCodec.i32(b, o, 0) + " cutoff="
                    + InputCodec.i64(b, o, 4) + " fixing=" + InputCodec.i64(b, o, 12);
            case InputCodec.FIXING_RATE -> "FIXING_RATE event=" + InputCodec.i32(b, o, 0) + " instr=" + InputCodec.i32(b, o, 4)
                    + " rate=" + InputCodec.i64(b, o, 8);
            case InputCodec.SESSION_STATE -> "SESSION_STATE instr=" + InputCodec.i32(b, o, 0) + " "
                    + Wire.SESSION_STATES.of(InputCodec.i8(b, o, 4));
            case InputCodec.MD_TICK -> "MD_TICK boundary=" + InputCodec.mdTickBoundary(b, o);
            default -> "UNKNOWN type=" + InputCodec.type(b, o);
        };
        return "seq=" + seq + " time=" + time + " " + body;
    }

    public static String output(OutputReader e) {
        String body = switch (e.type) {
            case OutputEncoder.ACCEPTED -> "ACCEPTED id=" + e.orderId;
            case OutputEncoder.REJECTED -> "REJECTED id=" + e.orderId + " " + clob.sequencer.Wire.REJECT_REASONS.of(e.reason);
            case OutputEncoder.TRADE -> "TRADE trade=" + e.tradeId + " instr=" + e.instrumentId + " aggressor="
                    + e.aggressorOrderId + " passive=" + e.passiveOrderId + " " + clob.sequencer.Wire.SIDES.of(e.side) + " px=" + e.price
                    + "e-" + e.priceDecimals + " qty=" + e.qty + " aggLeaves=" + e.aggressorLeaves + " passLeaves=" + e.passiveLeaves;
            case OutputEncoder.CANCELLED -> "CANCELLED id=" + e.orderId + " " + clob.sequencer.Wire.CANCEL_REASONS.of(e.reason) + " qty=" + e.qty;
            case OutputEncoder.REPLACED -> "REPLACED id=" + e.orderId + " px=" + e.price + " qty=" + e.qty + " leaves=" + e.leaves;
            case OutputEncoder.REQUEST_REJECTED -> "REQUEST_REJECTED id=" + e.orderId + " " + clob.sequencer.Wire.REQUEST_TYPES.of(e.requestType)
                    + " " + clob.sequencer.Wire.REQUEST_REJECT_REASONS.of(e.reason);
            case OutputEncoder.REQUEST_QUEUED -> "REQUEST_QUEUED id=" + e.orderId + " " + clob.sequencer.Wire.REQUEST_TYPES.of(e.requestType)
                    + " until=" + e.releaseTime;
            case OutputEncoder.REPRICED -> "REPRICED id=" + e.orderId + " px=" + e.price;
            case OutputEncoder.SUSPENDED -> "SUSPENDED id=" + e.orderId;
            case OutputEncoder.FIXING_MATCHED -> "FIXING_MATCHED match=" + e.tradeId + " instr=" + e.instrumentId + " event="
                    + e.fixingEventId + " aggressor=" + e.aggressorOrderId + " passive=" + e.passiveOrderId + " qty=" + e.qty;
            case OutputEncoder.RACE_STARTED -> "RACE_STARTED race=" + e.raceId + " instr=" + e.instrumentId + " "
                    + (e.taker ? "TAKER " : "MAKER ") + clob.sequencer.Wire.SIDES.of(e.side) + " px=" + e.price + " expiry=" + e.expiry
                    + " seed=" + e.seed;
            case OutputEncoder.JOINED_RACE -> "JOINED_RACE id=" + e.orderId + " race=" + e.raceId;
            case OutputEncoder.SESSION_STATE -> "SESSION_STATE instr=" + e.instrumentId + " " + clob.sequencer.Wire.SESSION_STATES.of(e.sessionState);
            default -> "UNKNOWN type=" + e.type;
        };
        return "time=" + e.time + " " + body;
    }
}
