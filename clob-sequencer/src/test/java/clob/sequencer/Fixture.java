package clob.sequencer;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.CancelSource;
import clob.engine.EngineConfig;
import clob.engine.EngineListener;
import clob.engine.MatchingEngine;
import clob.engine.NewOrderCommand;
import clob.engine.ReplaceCommand;
import clob.refdata.FixingEvent;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.agrona.concurrent.UnsafeBuffer;

/** Shared reference data, credit setup and a seeded random input stream, all as encoded inputs. */
final class Fixture {
    static final long MS = 1_000_000L;
    static final int ACCOUNTS = 12;
    static final long T0 = 1_800_000_000_000_000_000L; // an epoch-nanos start time

    static RefData refData() {
        RefData.Builder b = RefData.builder()
                .add(Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(0).priceIncrement(1).minQty(1000)
                        .qtyIncrement(500).maxQty(1_000_000).minTtlNanos(2 * MS).takerRaceNanos(3 * MS)
                        .makerRaceNanos(2 * MS).queueCancels(true).matchPriority(MatchPriority.VISIBILITY).build())
                .add(Instrument.builder(1, "USD/JPY", "SPOT").pricePrecision(0).priceIncrement(1).minQty(1000)
                        .qtyIncrement(1000).maxQty(1_000_000).minTtlNanos(0).matchPriority(MatchPriority.PRICE).build())
                .addFixingEvent(new FixingEvent(0, "TEST", LocalTime.of(16, 0), ZoneId.of("UTC"), LocalTime.of(15, 55)));
        for (int a = 1; a <= ACCOUNTS; a++) {
            b.entitle(a, a);
        }
        return b.build();
    }

    static MatchingEngine engine(RefData refData, EngineListener listener) {
        EngineConfig d = EngineConfig.defaults();
        return new MatchingEngine(refData, new EngineConfig(4096, 1_000_000, 1024, 64, 1024, 7L),
                new CreditEngine(ACCOUNTS, refData), listener);
    }

    /** Collects encoded output in order. */
    static final class Capture implements OutputSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int events;

        @Override
        public void write(org.agrona.DirectBuffer buffer, int offset, int length) {
            byte[] tmp = new byte[length];
            buffer.getBytes(offset, tmp);
            bytes.write(tmp, 0, length);
            events++;
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }

    private final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
    final List<byte[]> inputs = new ArrayList<>();

    private void add(int length) {
        byte[] copy = new byte[length];
        buf.getBytes(0, copy);
        inputs.add(copy);
    }

    /** Credit (random lines including DSL/NOP), USD rates, value dates and a fixing occurrence. */
    Fixture admin(long seed) {
        Random r = new Random(seed);
        for (String ccy : List.of("EUR", "USD")) {
            add(InputCodec.usdRate(buf, 0, ccy, CreditEngine.RATE_SCALE));
        }
        add(InputCodec.valueDates(buf, 0, 0, 20_000, -1));
        add(InputCodec.valueDates(buf, 0, 1, 20_000, -1));
        int line = 1;
        for (int a = 1; a <= ACCOUNTS; a++) {
            for (int b = 1; b <= ACCOUNTS; b++) {
                if (a == b || r.nextInt(10) == 0) {
                    continue;
                }
                int roll = r.nextInt(3);
                CreditType type = roll == 0 ? CreditType.UNLIMITED : roll == 1 ? CreditType.DSL : CreditType.NOP;
                add(InputCodec.creditLine(buf, 0, line, a, type, 5_000L * (1 + r.nextInt(10))));
                add(InputCodec.creditRelationship(buf, 0, a, b, CreditEngine.ALL_INSTRUMENTS, line++));
            }
        }
        add(InputCodec.fixingSchedule(buf, 0, 0, T0 + 300 * MS, T0 + 400 * MS));
        return this;
    }

    /** Seeded random flow: every order type, cancels, amends, mass cancels, a halt and reopen. */
    Fixture flow(long seed, int count) {
        Random r = new Random(seed);
        NewOrderCommand c = new NewOrderCommand();
        ReplaceCommand rc = new ReplaceCommand();
        for (int i = 1; i <= count; i++) {
            int account = 1 + r.nextInt(ACCOUNTS);
            int roll = r.nextInt(100);
            if (roll < 60) {
                c.reset();
                c.orderId = i;
                c.instrumentId = r.nextInt(2);
                c.accountId = account;
                c.userId = account;
                c.side = r.nextBoolean() ? Side.BUY : Side.SELL;
                c.qty = 1000 + 1000L * r.nextInt(5);
                c.tif = TimeInForce.values()[r.nextInt(TimeInForce.values().length)];
                c.expireTime = T0 + (long) i * MS + r.nextInt(20) * MS;
                int kind = r.nextInt(12);
                if (kind < 6) {
                    c.type = OrderType.LIMIT;
                    c.price = 90 + r.nextInt(10);
                    if (r.nextInt(4) == 0) {
                        c.maxShow = r.nextBoolean() ? 0 : 1000;
                    }
                } else if (kind < 8) {
                    c.type = OrderType.PEGGED;
                    c.pegReference = r.nextBoolean() ? PegReference.MID : PegReference.NEAR_TOUCH;
                    c.discretion = r.nextInt(3) - 1;
                } else if (kind < 10) {
                    c.type = OrderType.MID;
                    c.discretion = r.nextInt(3) - 1;
                } else if (kind < 11) {
                    c.type = OrderType.FIXING;
                    c.fixingEventId = 0;
                    c.tif = TimeInForce.DAY;
                } else {
                    c.type = OrderType.MARKET;
                    c.tif = TimeInForce.IOC;
                }
                add(InputCodec.newOrder(buf, 0, c));
            } else if (roll < 80) {
                add(InputCodec.cancel(buf, 0, 1 + r.nextInt(i), account, CancelSource.PARTICIPANT));
            } else if (roll < 92) {
                rc.reset();
                rc.orderId = 1 + r.nextInt(i);
                rc.userId = account;
                rc.price = 90 + r.nextInt(10);
                rc.qty = 1000 + 1000L * r.nextInt(5);
                add(InputCodec.replace(buf, 0, rc));
            } else if (roll < 94) {
                add(InputCodec.massCancel(buf, 0, clob.engine.MassCancelScope.ACCOUNT, account, account,
                        CancelSource.PARTICIPANT));
            } else if (roll < 95) {
                add(InputCodec.sessionState(buf, 0, 0, clob.engine.SessionState.HALTED));
            } else if (roll < 97) {
                add(InputCodec.sessionState(buf, 0, 0, clob.engine.SessionState.OPEN));
            } else {
                add(InputCodec.timeAdvance(buf, 0));
            }
        }
        add(InputCodec.sessionState(buf, 0, -1, clob.engine.SessionState.OPEN));
        add(InputCodec.fixingRate(buf, 0, 0, 0, 95));
        add(InputCodec.endOfDay(buf, 0));
        return this;
    }

    /** A clock the test drives: each read advances by a pseudo-random 0–3ms step. */
    static final class StepClock implements Clock {
        private final Random r;
        long now;

        StepClock(long seed, long start) {
            this.r = new Random(seed);
            this.now = start;
        }

        @Override
        public long nanos() {
            now += r.nextInt(4) * MS / 2;
            return now;
        }
    }

    static UnsafeBuffer wrap(byte[] message) {
        UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        b.putBytes(0, message);
        return b;
    }

    static byte[] copyOf(byte[] a) {
        return Arrays.copyOf(a, a.length);
    }
}
