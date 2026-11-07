package clob.testkit;

import clob.common.Side;
import clob.engine.CancelReason;
import clob.engine.RejectReason;
import clob.engine.RequestRejectReason;
import clob.engine.RequestType;

/** Recorded engine output, as comparable values. */
public sealed interface Event {
    long time();

    record Accepted(long time, long orderId) implements Event {}

    record Rejected(long time, long orderId, RejectReason reason) implements Event {}

    record Trade(long time, long tradeId, int instrumentId, long aggressorOrderId, long passiveOrderId,
            Side aggressorSide, long price, long qty, long aggressorLeaves, long passiveLeaves) implements Event {}

    record Cancelled(long time, long orderId, CancelReason reason, long qty) implements Event {}

    record Replaced(long time, long orderId, long price, long qty, long leaves) implements Event {}

    record RequestRejected(long time, long orderId, RequestType type, RequestRejectReason reason) implements Event {}

    record Queued(long time, long orderId, RequestType type, long releaseTime) implements Event {}
}
