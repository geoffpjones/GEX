package clob.sequencer;

import org.agrona.DirectBuffer;

/** Delivers each engine output event to several sinks (gateway, drop copy/ops, ...), in order. */
public final class FanOutSink implements OutputSink {
    private final OutputSink[] sinks;

    public FanOutSink(OutputSink... sinks) {
        this.sinks = sinks.clone();
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        for (OutputSink s : sinks) {
            s.write(buffer, offset, length);
        }
    }
}
