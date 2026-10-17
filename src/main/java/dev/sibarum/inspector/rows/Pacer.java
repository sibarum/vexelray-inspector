package dev.sibarum.inspector.rows;

import java.util.concurrent.locks.LockSupport;

/**
 * The wall-clock half of a replay: what time it is, and waiting until a time. An interface so the pacing can be
 * tested without waiting.
 */
public interface Pacer {

    /** Monotonic nanos, from any origin. */
    long now();

    /**
     * Return at or after {@code nanos} on {@link #now}'s clock, or early when the thread is interrupted. The
     * caller checks for interruption.
     */
    void waitUntil(long nanos);

    /** The real clock: {@link System#nanoTime}, parking the calling thread. */
    Pacer SYSTEM = new Pacer() {
        @Override
        public long now() {
            return System.nanoTime();
        }

        @Override
        public void waitUntil(long nanos) {
            long left;
            while ((left = nanos - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(left);
            }
        }
    };
}
