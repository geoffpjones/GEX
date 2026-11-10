package clob.app;

import clob.sequencer.Journal;
import java.util.function.Consumer;

/** Forces the journal to disk on an interval, which bounds what a power loss can lose. */
final class JournalFlusher implements AutoCloseable {
    private final Thread thread;
    private volatile boolean stopped;

    JournalFlusher(Journal journal, long intervalMillis, Consumer<Throwable> onFailure) {
        thread = new Thread(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(intervalMillis);
                    journal.flush();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    onFailure.accept(e);
                    return;
                }
            }
        }, "clob-journal-flusher");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
