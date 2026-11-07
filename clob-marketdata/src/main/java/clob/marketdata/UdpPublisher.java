package clob.marketdata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.Map;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Unicast UDP delivery (confirmed acceptable for screened data, PLAN Q12): one datagram per update to the
 * subscriber's endpoint, with a per-subscription sequence number for gap detection. Unscreened data can
 * use the same format on a multicast group.
 */
public final class UdpPublisher implements MarketDataSink, AutoCloseable {
    private final DatagramChannel channel;
    private final ByteBuffer packet = ByteBuffer.allocateDirect(65_000);
    private final UnsafeBuffer buffer = new UnsafeBuffer(packet);
    private final Map<Long, SocketAddress> endpoints = new HashMap<>();
    private final Map<Long, long[]> sequences = new HashMap<>();

    public UdpPublisher() {
        try {
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Routes a subscription's updates to an endpoint (call before subscribing). */
    public synchronized void register(long subscriptionId, SocketAddress endpoint) {
        endpoints.put(subscriptionId, endpoint);
        sequences.put(subscriptionId, new long[1]);
    }

    @Override
    public synchronized void publish(long subscriptionId, long boundary, BookView view) {
        SocketAddress to = endpoints.get(subscriptionId);
        if (to == null) {
            return;
        }
        long seq = ++sequences.get(subscriptionId)[0];
        int length = SnapshotCodec.encode(buffer, 0, subscriptionId, seq, boundary, view);
        packet.clear().limit(length);
        try {
            channel.send(packet, to); // non-blocking: a full socket buffer drops the datagram (receivers see a gap)
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
