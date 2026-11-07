package clob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clob.common.Side;
import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.refdata.Instrument;
import clob.refdata.MatchPriority;
import clob.refdata.OrderType;
import clob.refdata.PegReference;
import clob.refdata.RefData;
import clob.refdata.TimeInForce;
import java.lang.management.ManagementFactory;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Guards the zero-allocation claim: after warm-up, a realistic mixed flow (every order type, credit
 * limits, pegs, mid, races, cancels and amends) must not allocate on the engine thread.
 */
class AllocationTest {
    static final int ACCOUNTS = 32;

    @Test
    void steadyStateMatchingDoesNotAllocate() {
        RefData.Builder rb = RefData.builder()
                .add(Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(0).minQty(1000).qtyIncrement(1000)
                        .maxQty(1_000_000).minTtlNanos(0).takerRaceNanos(3_000).makerRaceNanos(2_000).queueCancels(true)
                        .pegThrottleNanos(20_000).matchPriority(MatchPriority.VISIBILITY).build())
                .add(Instrument.builder(1, "USD/JPY", "SPOT").pricePrecision(0).minQty(1000).qtyIncrement(1000)
                        .maxQty(1_000_000).minTtlNanos(500).matchPriority(MatchPriority.PRICE).build());
        for (int a = 1; a <= ACCOUNTS; a++) {
            rb.entitle(a, a);
        }
        RefData refData = rb.build();
        MatchingEngine e = new MatchingEngine(refData, EngineConfig.defaults(), new CreditEngine(ACCOUNTS, refData),
                NullEngineListener.INSTANCE);
        e.onUsdRate("EUR", CreditEngine.RATE_SCALE, 0);
        e.onUsdRate("USD", CreditEngine.RATE_SCALE, 0);
        e.onValueDates(0, 20_000, -1, 0);
        e.onValueDates(1, 20_000, -1, 0);
        Random r = new Random(1);
        int line = 1;
        for (int a = 1; a <= ACCOUNTS; a++) {
            e.onCreditLine(line, a, CreditType.UNLIMITED, 0, 0);
            int unlimited = line++;
            e.onCreditLine(line, a, CreditType.NOP, 50_000_000L, 0);
            int nop = line++;
            for (int b = 1; b <= ACCOUNTS; b++) {
                if (a != b && r.nextInt(10) > 0) {
                    e.onCreditRelationship(a, b, CreditEngine.ALL_INSTRUMENTS, r.nextBoolean() ? unlimited : nop, 0);
                }
            }
        }
        Flow flow = new Flow(e, 7);
        flow.run(1_000_000); // warm up past every pool's and table's high-water mark
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(tid);
        long trades = flow.run(500_000);
        long allocated = threads.getThreadAllocatedBytes(tid) - before;
        long before2 = threads.getThreadAllocatedBytes(tid);
        flow.run(500_000);
        long allocated2 = threads.getThreadAllocatedBytes(tid) - before2;
        System.out.println("allocation after warm-up: " + allocated + " then " + allocated2 + " bytes");
        allocated += allocated2;
        assertTrue(trades > 0 || e.liveOrderCount() > 0);
        assertEquals(0, allocated, "engine allocated " + allocated + " bytes in steady state");
    }

    /** Allocation-free random flow (reused commands, a fixed ring of at most 512 live orders). */
    static final class Flow {
        final MatchingEngine e;
        final Random r;
        final NewOrderCommand c = new NewOrderCommand();
        final ReplaceCommand rc = new ReplaceCommand();
        final long[] recent = new long[512];
        final int[] accounts = new int[512];

        int accountOf(long orderId) {
            return accounts[(int) (orderId % recent.length)];
        }
        long nextId = 1;
        long time = 1;

        Flow(MatchingEngine e, long seed) {
            this.e = e;
            this.r = new Random(seed);
        }

        long run(int events) {
            long count = 0;
            for (int i = 0; i < events; i++) {
                time += 1 + r.nextInt(400);
                int roll = r.nextInt(100);
                int account = 1 + r.nextInt(ACCOUNTS);
                if (roll < 55) {
                    // Bounded book: each new order evicts (cancels) the order that held its ring slot.
                    int slot = (int) (nextId % recent.length);
                    if (recent[slot] != 0) {
                        e.onCancel(recent[slot], accountOf(recent[slot]), CancelSource.ADMIN, time);
                    }
                    c.reset();
                    c.orderId = nextId;
                    recent[slot] = nextId;
                    accounts[slot] = account;
                    nextId++;
                    c.instrumentId = r.nextInt(2);
                    c.accountId = account;
                    c.userId = account;
                    c.side = r.nextBoolean() ? Side.BUY : Side.SELL;
                    c.qty = 1000L * (1 + r.nextInt(5));
                    c.tif = r.nextInt(8) == 0 ? TimeInForce.IOC : TimeInForce.GTC;
                    int kind = r.nextInt(10);
                    if (kind < 6) {
                        c.type = OrderType.LIMIT;
                        c.price = 90 + r.nextInt(20);
                        if (r.nextInt(4) == 0) {
                            c.maxShow = r.nextBoolean() ? 0 : 1000;
                        }
                    } else if (kind < 8) {
                        c.type = OrderType.PEGGED;
                        c.pegReference = r.nextBoolean() ? PegReference.MID : PegReference.NEAR_TOUCH;
                        c.discretion = r.nextInt(3) - 1;
                    } else if (kind < 9) {
                        c.type = OrderType.MID;
                        c.discretion = r.nextInt(3) - 1;
                    } else {
                        c.type = OrderType.MARKET;
                        c.tif = TimeInForce.IOC;
                    }
                    e.onNewOrder(c, time);
                    count++;
                } else if (roll < 90) {
                    long id = recent[r.nextInt(recent.length)];
                    e.onCancel(id, account, CancelSource.PARTICIPANT, time);
                } else {
                    rc.reset();
                    rc.orderId = recent[r.nextInt(recent.length)];
                    rc.userId = account;
                    rc.price = 90 + r.nextInt(20);
                    rc.qty = 1000L * (1 + r.nextInt(5));
                    e.onReplace(rc, time);
                }
            }
            return count;
        }
    }
}
