package clob.sequencer;

import clob.engine.MatchingEngine;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
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
 *
 * <p>Failure is fatal: the first error from any agent is recorded, new submissions are refused, every
 * agent stops, and retry loops exit, so a broken stage cannot look healthy or hang a healthy one.
 */
public final class Pipeline implements AutoCloseable {
    private static final int MSG = 1;
    private static final int FENCE = 2;
    private static final long DEFAULT_DRAIN_NANOS = 60_000_000_000L;

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
    private final List<Follower> followers = new ArrayList<>();
    private final int ringBytes;
    private final java.util.function.Supplier<IdleStrategy> idleStrategies;
    private volatile Throwable failure;
    private volatile boolean ingressClosed;
    private volatile boolean stopping;
    private volatile boolean started;
    private final AtomicLong fenceRequested = new AtomicLong();
    private final AtomicLong fenceAcknowledged = new AtomicLong();
    private final AtomicLong fenceSequence = new AtomicLong();
    private final UnsafeBuffer fenceBuffer = new UnsafeBuffer(new byte[8]);
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Throwable>> failureListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Another consumer of the sequenced input stream (e.g. the market data replica) on its own thread. */
    private final class Follower implements Agent {
        final RingBuffer ring;
        final InputHandler handler;
        final AtomicLong processed = new AtomicLong();
        AgentRunner runner;

        Follower(InputHandler handler, long lastSequence) {
            this.ring = new OneToOneRingBuffer(ring(ringBytes));
            this.handler = handler;
            this.processed.set(lastSequence);
        }

        @Override
        public int doWork() {
            checkRunning();
            int work = ring.read((type, buffer, index, length) -> {
                handler.onInput(buffer, index, length);
                processed.lazySet(InputCodec.sequence(buffer, index));
            }, 256);
            return work;
        }

        @Override
        public String roleName() {
            return "clob-follower";
        }
    }

    public Pipeline(MatchingEngine engine, Journal journal, Clock clock, EngineDispatcher.ErrorListener errors,
            long lastSequence, long lastTime, int ringBytes) {
        this(engine, journal, clock, errors, lastSequence, lastTime, ringBytes, Pipeline::backoff);
    }

    /** {@code idleStrategies} supplies one strategy per agent (busy-spin on isolated cores in production). */
    public Pipeline(MatchingEngine engine, Journal journal, Clock clock, EngineDispatcher.ErrorListener errors,
            long lastSequence, long lastTime, int ringBytes, java.util.function.Supplier<IdleStrategy> idleStrategies) {
        this.idleStrategies = idleStrategies;
        this.engine = engine;
        this.ringBytes = ringBytes;
        this.inbound = new ManyToOneRingBuffer(ring(ringBytes));
        this.toEngine = new OneToOneRingBuffer(ring(ringBytes));
        this.dispatcher = new EngineDispatcher(engine, errors);
        this.sequencer = new Sequencer(journal, clock, this::forward, lastSequence, lastTime);
        this.sequenced.set(lastSequence);
        this.processed.set(lastSequence);
        this.nextDeadline.set(engine.nextTimerDeadline());
        this.sequencerRunner = new AgentRunner(idleStrategies.get(), this::fail, null, new SequencerAgent());
        this.engineRunner = new AgentRunner(idleStrategies.get(), this::fail, null, new EngineAgent());
    }

    private static UnsafeBuffer ring(int bytes) {
        if (Integer.bitCount(bytes) != 1) {
            throw new IllegalArgumentException("ringBytes must be a power of two");
        }
        return new UnsafeBuffer(ByteBuffer.allocateDirect(bytes + RingBufferDescriptor.TRAILER_LENGTH));
    }

    /** The default idle strategy: parks up to 100 µs, which suits shared hosts. */
    public static IdleStrategy backoff() {
        return new BackoffIdleStrategy(100, 10, 1_000, 100_000);
    }

    private void fail(Throwable t) {
        if (t instanceof AgentTerminationException) {
            return;
        }
        if (failure == null) {
            failure = t;
            for (java.util.function.Consumer<Throwable> l : failureListeners) {
                l.accept(t);
            }
        }
    }

    private void checkRunning() {
        if (failure != null) {
            throw new AgentTerminationException();
        }
    }

    /** Called once, on an agent thread, with the first fatal error. */
    public void onFailure(java.util.function.Consumer<Throwable> listener) {
        failureListeners.add(listener);
    }

    public Throwable failure() {
        return failure;
    }

    /** Adds a consumer of every sequenced input, on its own thread. Call before {@link #start()}. */
    public void addFollower(InputHandler handler) {
        Follower f = new Follower(handler, sequenced.get());
        f.runner = new AgentRunner(idleStrategies.get(), this::fail, null, f);
        followers.add(f);
    }

    /** Emits market data cycle ticks on the wall-clock grid. Call before {@link #start()}. */
    public void enableMarketDataTicks(long intervalNanos) {
        sequencer.enableMarketDataTicks(intervalNanos);
    }

    /** Injects END_OF_DAY inputs at this local time each day. Call before {@link #start()}. */
    public void enableEndOfDay(java.time.LocalTime time, java.time.ZoneId zone) {
        sequencer.enableEndOfDay(time, zone);
    }

    public boolean started() {
        return started;
    }

    public void start() {
        started = true;
        for (Follower f : followers) {
            AgentRunner.startOnThread(f.runner);
        }
        AgentRunner.startOnThread(engineRunner);
        AgentRunner.startOnThread(sequencerRunner);
    }

    /** Called by gateways on any thread. False if the ring is full, ingress is closed or the pipeline failed. */
    public boolean submit(DirectBuffer buffer, int offset, int length) {
        if (ingressClosed || failure != null) {
            return false;
        }
        return inbound.write(MSG, buffer, offset, length);
    }

    /** Stops accepting new submissions. Inputs already in the ring are still processed. */
    public void closeIngress() {
        ingressClosed = true;
    }

    public boolean ingressClosed() {
        return ingressClosed;
    }

    /** Waits until everything submitted so far has been sequenced and processed by the engine and followers. */
    public void drain() {
        if (!drain(DEFAULT_DRAIN_NANOS)) {
            throw new IllegalStateException("Pipeline did not drain in time");
        }
    }

    /**
     * As {@link #drain()}, within {@code timeoutNanos}. A fence goes through the inbound ring behind
     * everything already submitted; once the sequencer reaches it, every earlier input has a sequence
     * number, and the watermark it records is what the engine and followers must then acknowledge.
     * False on timeout. Throws if the pipeline has failed.
     */
    public boolean drain(long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        long id = fenceRequested.incrementAndGet();
        synchronized (fenceBuffer) {
            fenceBuffer.putLong(0, id, InputCodec.ORDER);
            while (!inbound.write(FENCE, fenceBuffer, 0, 8)) {
                if (!waitStep(deadline)) {
                    return false;
                }
            }
        }
        while (fenceAcknowledged.get() < id) {
            if (!waitStep(deadline)) {
                return false;
            }
        }
        long watermark = fenceSequence.get();
        while (processed.get() < watermark || followersBehind(watermark)) {
            if (!waitStep(deadline)) {
                return false;
            }
        }
        return true;
    }

    private boolean waitStep(long deadline) {
        if (failure != null) {
            throw new IllegalStateException("Pipeline failed", failure);
        }
        if (System.nanoTime() - deadline > 0) {
            return false;
        }
        Thread.onSpinWait();
        return true;
    }

    private boolean followersBehind(long watermark) {
        for (Follower f : followers) {
            if (f.processed.get() < watermark) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        close(DEFAULT_DRAIN_NANOS);
    }

    /** Drains within the time limit, then stops every agent regardless. Throws if the pipeline failed. */
    public void close(long timeoutNanos) {
        closeIngress();
        long deadline = System.nanoTime() + timeoutNanos;
        boolean drained = false;
        try {
            drained = failure == null && drain(timeoutNanos);
            if (!drained) {
                stopping = true; // releases the sequencer if it is waiting on a stuck consumer
            }
            sequencerRunner.close();
            // The sequencer may have injected timer inputs since the drain; consumers must see those too.
            long last = sequencer.lastSequence();
            while (drained && (processed.get() < last || followersBehind(last))) {
                drained = waitStep(deadline);
            }
        } finally {
            stopping = true;
            sequencerRunner.close();
            engineRunner.close();
            for (Follower f : followers) {
                f.runner.close();
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Pipeline failed", failure);
        }
        if (!drained) {
            throw new IllegalStateException("Pipeline did not drain before shutdown");
        }
    }

    public long lastSequence() {
        return sequenced.get();
    }

    /** Last sequence the engine has fully processed. */
    public long processedSequence() {
        return processed.get();
    }

    /** Last sequence processed by the slowest follower. */
    public long slowestFollowerSequence() {
        long min = sequenced.get();
        for (Follower f : followers) {
            min = Math.min(min, f.processed.get());
        }
        return min;
    }

    /** Bytes waiting in the inbound ring. */
    public int inboundQueued() {
        return inbound.size();
    }

    public long maxMarketDataLatenessNanos() {
        return sequencer.maxMdLatenessNanos();
    }

    public MatchingEngine engine() {
        return engine;
    }

    private void forward(DirectBuffer buffer, int offset, int length) {
        while (!toEngine.write(MSG, buffer, offset, length)) {
            stopIfNeeded();
            Thread.onSpinWait();
        }
        for (int i = 0; i < followers.size(); i++) {
            while (!followers.get(i).ring.write(MSG, buffer, offset, length)) {
                stopIfNeeded();
                Thread.onSpinWait();
            }
        }
    }

    private void stopIfNeeded() {
        if (stopping || failure != null) {
            throw new AgentTerminationException();
        }
    }

    private final class SequencerAgent implements Agent {
        private long injectedFor = Long.MIN_VALUE;

        @Override
        public int doWork() {
            checkRunning();
            int work = inbound.read((type, buffer, index, length) -> {
                if (type == FENCE) {
                    fenceSequence.set(sequencer.lastSequence());
                    fenceAcknowledged.set(buffer.getLong(index, InputCodec.ORDER));
                } else {
                    sequencer.onCommand(buffer, index, length);
                }
            }, 256);
            long deadline = nextDeadline.get();
            if (sequencer.poll(deadline != injectedFor ? deadline : Long.MAX_VALUE)) {
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
            checkRunning();
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
