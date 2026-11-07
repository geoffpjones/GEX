package clob.sequencer;

import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/** Engine output → one consumer thread (a gateway). Blocks the engine (spins) if the consumer falls behind. */
public final class OutputRing implements OutputSink {
    private final OneToOneRingBuffer ring;

    public OutputRing(int bytes) {
        ring = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(bytes + RingBufferDescriptor.TRAILER_LENGTH)));
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        while (!ring.write(1, buffer, offset, length)) {
            Thread.onSpinWait();
        }
    }

    public int poll(MessageHandler handler, int limit) {
        return ring.read(handler, limit);
    }
}
