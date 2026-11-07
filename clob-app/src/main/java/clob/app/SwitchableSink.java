package clob.app;

import clob.sequencer.OutputSink;
import org.agrona.DirectBuffer;

/** Passes engine output on only when enabled: off during recovery replay and while a standby. */
final class SwitchableSink implements OutputSink {
    private final OutputSink target;
    private volatile boolean enabled;

    SwitchableSink(OutputSink target) {
        this.target = target;
    }

    void enable() {
        enabled = true;
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        if (enabled) {
            target.write(buffer, offset, length);
        }
    }
}
