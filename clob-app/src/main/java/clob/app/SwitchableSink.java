package clob.app;

import clob.sequencer.OutputSink;
import org.agrona.DirectBuffer;

/**
 * Engine output goes to the live consumers only when enabled. During recovery and while a standby it
 * goes to the replay tap instead, which rebuilds gateway state without delivering anything.
 */
final class SwitchableSink implements OutputSink {
    private final OutputSink target;
    private final OutputSink replayTap;
    private volatile boolean enabled;

    SwitchableSink(OutputSink target, OutputSink replayTap) {
        this.target = target;
        this.replayTap = replayTap;
    }

    void enable() {
        enabled = true;
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        if (enabled) {
            target.write(buffer, offset, length);
        } else {
            replayTap.write(buffer, offset, length);
        }
    }
}
