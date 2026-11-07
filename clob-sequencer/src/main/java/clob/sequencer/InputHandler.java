package clob.sequencer;

import org.agrona.DirectBuffer;

/** Receives one encoded input message. */
@FunctionalInterface
public interface InputHandler {
    void onInput(DirectBuffer buffer, int offset, int length);
}
