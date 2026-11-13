package org.coryd.notification.broadcast;

/**
 * Spaces sends out to at most ratePerSecond - the next send waits until 1/rate seconds after the previous one started.
 * The clock and the sleep are swappable for tests.
 */
public class SendPacer {

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public interface Ticker {
        long nanos();
    }

    private final long intervalNanos;
    private final Ticker ticker;
    private final Sleeper sleeper;
    private long next;

    public SendPacer(int ratePerSecond) {
        this(ratePerSecond, System::nanoTime, Thread::sleep);
    }

    public SendPacer(int ratePerSecond, Ticker ticker, Sleeper sleeper) {
        this.intervalNanos = 1_000_000_000L / Math.max(1, ratePerSecond);
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.next = ticker.nanos();
    }

    /** Blocks until the next send may start. */
    public void acquire() throws InterruptedException {
        long now = ticker.nanos();
        if (now < next) {
            long waitNanos = next - now;
            sleeper.sleep(Math.max(1, waitNanos / 1_000_000));
            now = next;
        }
        next = Math.max(now, next) + intervalNanos;
    }
}
