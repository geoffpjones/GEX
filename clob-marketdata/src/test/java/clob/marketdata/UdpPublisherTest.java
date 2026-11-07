package clob.marketdata;

import static clob.marketdata.MdHarness.MS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import clob.common.Side;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class UdpPublisherTest {
    @Test
    void deliversSequencedSnapshotsToTheSubscribersEndpoint() throws Exception {
        try (DatagramChannel receiver = DatagramChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                UdpPublisher udp = new UdpPublisher()) {
            receiver.socket().setSoTimeout(2000);
            MdHarness h = new MdHarness(MdHarness.fourDp().build());
            udp.register(7, receiver.getLocalAddress());
            h.md.subscribe(7, 0, 0, 0, false, udp);
            h.order(1, 1, Side.BUY, 10000, 1000).tick(5 * MS);
            h.order(2, 1, Side.SELL, 10010, 2000).tick(10 * MS);
            ByteBuffer packet = ByteBuffer.allocateDirect(65_000);
            UnsafeBuffer b = new UnsafeBuffer(packet);
            BookView v = SnapshotCodec.newView();
            for (long expectedSeq = 1; expectedSeq <= 2; expectedSeq++) {
                packet.clear();
                receiver.receive(packet);
                assertEquals(7, SnapshotCodec.subscriptionId(b, 0));
                assertEquals(expectedSeq, SnapshotCodec.sequence(b, 0));
                assertEquals(expectedSeq * 5 * MS, SnapshotCodec.boundary(b, 0));
                SnapshotCodec.decode(b, 0, v);
            }
            assertEquals("bids[10000x1000] asks[10010x2000]", v.toString());
        }
    }
}
