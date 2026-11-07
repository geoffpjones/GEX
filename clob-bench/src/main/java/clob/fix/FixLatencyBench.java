package clob.fix;

import clob.credit.CreditEngine;
import clob.credit.CreditType;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.refdata.Instrument;
import clob.refdata.RefData;
import clob.sequencer.Clock;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputRing;
import clob.sequencer.Pipeline;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * End-to-end latency: FIX NewOrderSingle over TCP → gateway → sequencer (stamp + journal) → engine →
 * execution report (New) back over TCP. Paced sending; latency is measured from each message's
 * scheduled send time (coordinated-omission corrected). Orders are non-crossing IOCs, so the book stays
 * empty. Options: {@code --rate N --orders N --warmup N}.
 */
public final class FixLatencyBench {
    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> o = new java.util.HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            o.put(args[i].substring(2), args[i + 1]);
        }
        int rate = Integer.parseInt(o.getOrDefault("rate", "10000"));
        int orders = Integer.parseInt(o.getOrDefault("orders", "100000"));
        int warmup = Integer.parseInt(o.getOrDefault("warmup", "50000"));

        RefData refData = RefData.builder().add(Instrument.builder(0, "EUR/USD", "SPOT").pricePrecision(5)
                .minQty(1000).qtyIncrement(1000).maxQty(10_000_000).minTtlNanos(0).build()).entitle(1, 1).build();
        OutputRing out = new OutputRing(1 << 22);
        MatchingEngine engine = new MatchingEngine(refData, EngineConfig.defaults(), new CreditEngine(4, refData),
                new OutputEncoder(out));
        Path dir = Files.createTempDirectory("clob-latency");
        Journal journal = Journal.open(dir, 64 << 20);
        Pipeline pipeline = new Pipeline(engine, journal, Clock.system(), (s, t, ty, r) -> { }, 0, 0, 1 << 20);
        pipeline.start();
        UnsafeBuffer cmd = new UnsafeBuffer(ByteBuffer.allocateDirect(InputCodec.MAX_LENGTH));
        pipeline.submit(cmd, 0, InputCodec.creditLine(cmd, 0, 1, 1, CreditType.UNLIMITED, 0));
        pipeline.submit(cmd, 0, InputCodec.usdRate(cmd, 0, "EUR", CreditEngine.RATE_SCALE));
        pipeline.drain();
        FixGateway gateway = new FixGateway(new FixGatewayConfig("CLOB", 0, 1,
                List.of(new FixSessionConfig("BENCH", "u1", "p1", 1, false, 0)), 5_000), refData, pipeline, out,
                System::currentTimeMillis);
        AgentRunner gw = new AgentRunner(new org.agrona.concurrent.BackoffIdleStrategy(100, 10, 1_000, 50_000), Throwable::printStackTrace, null, gateway);
        AgentRunner.startOnThread(gw);

        SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", gateway.port()));
        ch.socket().setTcpNoDelay(true);
        FixEncoder enc = new FixEncoder();
        long[] seq = {1};
        enc.begin("A", "BENCH", "CLOB", seq[0]++, System.currentTimeMillis(), false, 0).add(98, 0).add(108, 30)
                .add(141, 'Y').add(553, "u1").add(554, "p1");
        ch.write(ByteBuffer.wrap(enc.bytes(), 0, enc.finish()));

        int total = warmup + orders;
        long[] scheduled = new long[total];
        Histogram latency = new Histogram(60_000_000_000L, 3);
        int[] acked = {0};
        Thread reader = new Thread(() -> {
            FixParser p = new FixParser();
            ByteBuffer in = ByteBuffer.allocate(1 << 20);
            try {
                while (acked[0] < total) {
                    ch.read(in);
                    int start = 0;
                    int end = in.position();
                    byte[] b = in.array();
                    int len;
                    while ((len = FixParser.frame(b, start, end - start)) > 0) {
                        long now = System.nanoTime();
                        p.parse(b, start, len);
                        if (p.is(35, "8") && p.is(150, "0")) {
                            int n = (int) p.asLong(11);
                            if (n >= warmup) {
                                latency.recordValue(Math.max(0, now - scheduled[n]));
                            }
                            acked[0]++;
                        }
                        start += len;
                    }
                    in.limit(end).position(start);
                    in.compact();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        reader.start();
        Thread.sleep(500); // logon

        long interval = 1_000_000_000L / rate;
        long t0 = System.nanoTime() + 1_000_000;
        ByteBuffer wire = ByteBuffer.wrap(enc.bytes());
        for (int n = 0; n < total; n++) {
            long due = t0 + n * interval;
            scheduled[n] = due;
            enc.begin("D", "BENCH", "CLOB", seq[0]++, System.currentTimeMillis(), false, 0).add(11, n).add(1, 1)
                    .add(55, "EUR/USD").add(54, n % 2 == 0 ? '1' : '2').add(38, 1000).add(40, '2')
                    .add(44, n % 2 == 0 ? "1.00000" : "2.00000").add(59, '3');
            int len = enc.finish();
            while (System.nanoTime() < due) {
                Thread.onSpinWait();
            }
            wire.clear().limit(len);
            while (wire.hasRemaining()) {
                ch.write(wire);
            }
        }
        reader.join(30_000);
        System.out.printf("FIX round trip (NewOrderSingle -> ExecutionReport New), %,d orders at %,d/s, %d acked:%n",
                orders, rate, acked[0]);
        System.out.printf("  p50=%,d us p90=%,d us p99=%,d us p99.9=%,d us max=%,d us%n",
                latency.getValueAtPercentile(50) / 1000, latency.getValueAtPercentile(90) / 1000,
                latency.getValueAtPercentile(99) / 1000, latency.getValueAtPercentile(99.9) / 1000,
                latency.getMaxValue() / 1000);
        gw.close();
        pipeline.close();
        journal.close();
        System.exit(0);
    }
}
