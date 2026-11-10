package clob.sequencer;

import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * Engine output to one consumer thread. The writer spins if the consumer falls behind, because
 * execution reports and the drop copy are mandatory and must not be dropped. The spin ends when the
 * ring is aborted, which turns a stopped consumer into a failure instead of a hang.
 */
public final class OutputRing implements OutputSink {
    private final OneToOneRingBuffer ring;
    private volatile boolean aborted;

    public OutputRing(int bytes) {
        ring = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(bytes + RingBufferDescriptor.TRAILER_LENGTH)));
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        while (!ring.write(1, buffer, offset, length)) {
            if (aborted) {
                throw new IllegalStateException("Output ring aborted: its consumer has stopped");
            }
            Thread.onSpinWait();
        }
    }

    public int poll(MessageHandler handler, int limit) {
        return ring.read(handler, limit);
    }

    /** Bytes waiting. Zero means every event written has been fully handled by the consumer. */
    public int size() {
        return ring.size();
    }

    /** Makes writers stop waiting. Used at failure and at the end of a bounded shutdown. */
    public void abort() {
        aborted = true;
    }
}
