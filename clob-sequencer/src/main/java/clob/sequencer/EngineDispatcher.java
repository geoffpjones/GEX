package clob.sequencer;

import static clob.sequencer.InputCodec.i32;
import static clob.sequencer.InputCodec.i64;
import static clob.sequencer.InputCodec.i8;

import clob.credit.CreditRefusal;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import org.agrona.DirectBuffer;

/**
 * Decodes stamped inputs and applies them to the engine. Used both live (after the journal) and in
 * replay, so both paths run exactly the same code. Admin inputs the engine refuses (invalid credit
 * configuration, a fixing schedule or rate that doesn't apply) are reported to the {@link ErrorListener}.
 * The domain APIs validate before mutating and return typed refusals, so a refused input leaves state
 * unchanged in live and replay. Any exception that escapes is an invariant failure, not a refusal: it
 * is left to fail the pipeline.
 */
public final class EngineDispatcher implements InputHandler {
    /** Refused admin inputs. */
    public interface ErrorListener {
        void onInputRefused(long sequence, long time, int type, String reason);
    }

    private final MatchingEngine engine;
    private final ErrorListener errors;
    private final NewOrderCommand newOrder = new NewOrderCommand();
    private final ReplaceCommand replace = new ReplaceCommand();
    private long lastSequence;

    public EngineDispatcher(MatchingEngine engine, ErrorListener errors) {
        this.engine = engine;
        this.errors = errors;
    }

    public long lastSequence() {
        return lastSequence;
    }

    @Override
    public void onInput(DirectBuffer b, int o, int length) {
        long time = InputCodec.time(b, o);
        int type = InputCodec.type(b, o);
        lastSequence = InputCodec.sequence(b, o);
        switch (type) {
                case InputCodec.NEW_ORDER -> {
                    InputCodec.decodeNewOrder(b, o, newOrder.reset());
                    engine.onNewOrder(newOrder, time);
                }
                case InputCodec.CANCEL -> engine.onCancel(InputCodec.cancelOrderId(b, o), InputCodec.cancelUserId(b, o),
                        InputCodec.cancelSource(b, o), InputCodec.cancelRequestId(b, o), time);
                case InputCodec.REPLACE -> {
                    InputCodec.decodeReplace(b, o, replace.reset());
                    engine.onReplace(replace, time);
                }
                case InputCodec.MASS_CANCEL -> engine.onMassCancel(Wire.SCOPES.of(i8(b, o, 0)),
                        i32(b, o, 1), i32(b, o, 5), Wire.CANCEL_SOURCES.of(i8(b, o, 9)), time);
                case InputCodec.TIME_ADVANCE, InputCodec.MD_TICK -> engine.onTime(time);
                case InputCodec.END_OF_DAY -> engine.onEndOfDay(time);
                case InputCodec.CREDIT_LINE -> report(type, time, engine.onCreditLine(i32(b, o, 0), i32(b, o, 4),
                        Wire.CREDIT_TYPES.of(i8(b, o, 8)), i64(b, o, 9), time));
                case InputCodec.CREDIT_RELATIONSHIP -> report(type, time, engine.onCreditRelationship(i32(b, o, 0),
                        i32(b, o, 4), i32(b, o, 8), i32(b, o, 12), time));
                case InputCodec.USD_RATE -> report(type, time,
                        engine.onUsdRate(InputCodec.usdRateCurrency(b, o), i64(b, o, 8), time));
                case InputCodec.VALUE_DATES -> report(type, time,
                        engine.onValueDates(i32(b, o, 0), i32(b, o, 4), i32(b, o, 8), time));
                case InputCodec.VALUE_DATE_ROLL -> report(type, time, engine.onValueDateRoll(i32(b, o, 0), time));
                case InputCodec.FIXING_SCHEDULE -> {
                    if (!engine.onFixingSchedule(i32(b, o, 0), i64(b, o, 4), i64(b, o, 12), time)) {
                        errors.onInputRefused(lastSequence, time, type, "fixing schedule not applicable");
                    }
                }
                case InputCodec.FIXING_RATE -> {
                    if (!engine.onFixingRate(i32(b, o, 0), i32(b, o, 4), i64(b, o, 8), time)) {
                        errors.onInputRefused(lastSequence, time, type, "no fixing awaiting a rate");
                    }
                }
                case InputCodec.SESSION_STATE -> engine.onSessionState(i32(b, o, 0),
                        Wire.SESSION_STATES.of(i8(b, o, 4)), time);
                default -> errors.onInputRefused(lastSequence, time, type, "unknown input type");
        }
    }

    private void report(int type, long time, CreditRefusal refusal) {
        if (refusal != null) {
            errors.onInputRefused(lastSequence, time, type, refusal.name());
        }
    }
}
