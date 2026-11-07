package clob.sequencer;

import clob.engine.MatchingEngine;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * The threaded order path: gateways → (many-to-one ring) → sequencer thread (stamp, journal) →
 * (one-to-one ring) → engine thread (decode, match, emit). Journalling stays off the engine thread, as
 * the Phase 0 spike showed it must. Aeron IPC and Aeron Archive can later replace the rings and the
 * journal without changing either agent's logic.
 *
 * <p>The engine thread publishes its next timer deadline; the sequencer injects a time input once the
 * clock passes it (only once per deadline, until the engine has processed it).
 */
public final class Pipeline implements AutoCloseable {
    private static final int MSG = 1;

    private final RingBuffer inbound;
    private final RingBuffer toEngine;
    private final Sequencer sequencer;
    private final EngineDispatcher dispatcher;
    private final MatchingEngine engine;
    private final AtomicLong nextDeadline = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong sequenced = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AgentRunner sequencerRunner;
    private final AgentRunner engineRunner;
    private volatile Throwable failure;

    public Pipeline(MatchingEngine engine, Journal journal, Clock clock, EngineDispatcher.ErrorListener errors,
            long lastSequence, long lastTime, int ringBytes) {
        this.engine = engine;
        this.inbound = new ManyToOneRingBuffer(ring(ringBytes));
        this.toEngine = new OneToOneRingBuffer(ring(ringBytes));
        this.dispatcher = new EngineDispatcher(engine, errors);
        this.sequencer = new Sequencer(journal, clock, this::forward, lastSequence, lastTime);
        this.sequenced.set(lastSequence);
        this.processed.set(lastSequence);
        this.nextDeadline.set(engine.nextTimerDeadline());
        this.sequencerRunner = new AgentRunner(idle(), this::fail, null, new SequencerAgent());
        this.engineRunner = new AgentRunner(idle(), this::fail, null, new EngineAgent());
    }

    private static UnsafeBuffer ring(int bytes) {
        if (Integer.bitCount(bytes) != 1) {
            throw new IllegalArgumentException("ringBytes must be a power of two");
        }
        return new UnsafeBuffer(ByteBuffer.allocateDirect(bytes + RingBufferDescriptor.TRAILER_LENGTH));
    }

    private static BackoffIdleStrategy idle() {
        return new BackoffIdleStrategy(100, 10, 1_000, 100_000);
    }

    private void fail(Throwable t) {
        failure = t;
    }

    public void start() {
        AgentRunner.startOnThread(engineRunner);
        AgentRunner.startOnThread(sequencerRunner);
    }

    /** Called by gateways on any thread. False if the inbound ring is full (back-pressure). */
    public boolean submit(DirectBuffer buffer, int offset, int length) {
        return inbound.write(MSG, buffer, offset, length);
    }

    /** Waits until everything submitted so far has been sequenced and processed by the engine. */
    public void drain() {
        while (inbound.size() > 0 || sequenced.get() != processed.get()) {
            if (failure != null) {
                throw new IllegalStateException("Pipeline failed", failure);
            }
            Thread.onSpinWait();
        }
    }

    @Override
    public void close() {
        drain();
        sequencerRunner.close();
        engineRunner.close();
        if (failure != null) {
            throw new IllegalStateException("Pipeline failed", failure);
        }
    }

    public long lastSequence() {
        return sequenced.get();
    }

    public MatchingEngine engine() {
        return engine;
    }

    private void forward(DirectBuffer buffer, int offset, int length) {
        while (!toEngine.write(MSG, buffer, offset, length)) {
            Thread.onSpinWait();
        }
    }

    private final class SequencerAgent implements Agent {
        private long injectedFor = Long.MIN_VALUE;

        @Override
        public int doWork() {
            int work = inbound.read((type, buffer, index, length) ->
                    sequencer.onCommand(buffer, index, length), 256);
            long deadline = nextDeadline.get();
            if (deadline != injectedFor && sequencer.poll(deadline)) {
                injectedFor = deadline;
                work++;
            }
            sequenced.lazySet(sequencer.lastSequence());
            return work;
        }

        @Override
        public String roleName() {
            return "clob-sequencer";
        }
    }

    private final class EngineAgent implements Agent {
        @Override
        public int doWork() {
            int work = toEngine.read((type, buffer, index, length) -> dispatcher.onInput(buffer, index, length), 256);
            if (work > 0) {
                nextDeadline.set(engine.nextTimerDeadline());
                processed.lazySet(dispatcher.lastSequence());
            }
            return work;
        }

        @Override
        public String roleName() {
            return "clob-engine";
        }
    }
}
