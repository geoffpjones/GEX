package clob.sequencer;

import org.agrona.DirectBuffer;

/** Receives one encoded engine output event. */
@FunctionalInterface
public interface OutputSink {
    void write(DirectBuffer buffer, int offset, int length);
}
