package dev.sibarum.inspector.clock;

import dev.sibarum.inspector.rows.LinkSource;
import dev.sibarum.inspector.rows.Row;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The rows that have arrived and not been shown yet, between the source's thread and the timeline: a bounded ring
 * with one writer at a time and one reader.
 *
 * <p>The writer (the source's thread) never waits for the reader, and the reader (the timeline, inside the frame)
 * never waits at all: no lock on its side, and nothing allocated. When the ring is full a row is dropped and
 * counted, and the next row that fits is preceded by a {@code stream}/{@code lost} row naming the range, as the
 * link reports its own losses, so the escapement does not measure across it.
 *
 * <p>A run's beginning and end are kept in order with its rows, as markers. A row is released when show time has
 * reached it, or at once while a marker is waiting behind it: the run it belongs to is over, and its rows are
 * flushed rather than shown.
 *
 * <p>It is also the {@link ShowClock.Cues}: the bus's publishes ({@link BusRows#publish}) waiting to be shown.
 */
public final class Pending implements ShowClock.Cues {

    /** A run began ({@code begin}) or ended, and what the source said about it. */
    public record Marker(boolean begin, String text) {
    }

    /** How far a look ahead reads at most, so a frame's cost has a ceiling however far behind the ring is. */
    static final int SCAN = 4096;

    /** Slots only markers may use. */
    static final int RESERVE = 8;

    private final Object[] items;
    private final long[] times;
    private final boolean[] cues;
    private final int mask;
    private final AtomicInteger markers = new AtomicInteger();

    // Written by the reader, read by the writer: how far the reader has taken.
    private volatile long head;
    // Written by the writer, read by the reader: how far the writer has put.
    private volatile long tail;

    // The writer's own.
    private long lostFrom = -1;
    private long lostTo = -1;
    private long lostAt;
    private long dropped;

    /** @param capacity at least this many rows, rounded up to a power of two */
    public Pending(int capacity) {
        int n = Integer.highestOneBit(Math.max(2, capacity + RESERVE - 1)) << 1;
        items = new Object[n];
        times = new long[n];
        cues = new boolean[n];
        mask = n - 1;
    }

    // ------------------------------------------------------------------ the writer

    /** A row arrived. The writer's. */
    public synchronized void row(Row row) {
        if (lostFrom >= 0) {
            if (room() < 2) {
                drop(row);
                return;
            }
            put(new Row(lostFrom, lostAt, "-", "-", LinkSource.STREAM, LinkSource.LOST, lostFrom + ".." + lostTo,
                    Row.NO_FRAME), lostAt, false);
            lostFrom = -1;
        }
        if (room() < 1) {
            drop(row);
            return;
        }
        put(row, row.monoNanos(), BusRows.publish(row));
    }

    /**
     * A run began or ended. Markers have a reserve of their own that rows never use, so one is not dropped behind a
     * full ring of rows; only a reader that has stopped altogether, with {@link #RESERVE} markers unread, loses one.
     * The writer's.
     */
    public synchronized void marker(Marker marker) {
        if (items.length - (int) (tail - head) < 1) {
            return;
        }
        markers.incrementAndGet();
        put(marker, Long.MIN_VALUE, false);
    }

    /** Rows dropped because the ring was full, in this ring's life. */
    public synchronized long dropped() {
        return dropped;
    }

    private void drop(Row row) {
        dropped++;
        if (lostFrom < 0) {
            lostFrom = row.seq();
            lostAt = row.monoNanos();
        }
        lostTo = row.seq();
    }

    private int room() {
        return items.length - RESERVE - (int) (tail - head);
    }

    private void put(Object item, long time, boolean cue) {
        long t = tail;
        int i = (int) (t & mask);
        items[i] = item;
        times[i] = time;
        cues[i] = cue;
        tail = t + 1;           // publishes the slot to the reader
    }

    // ------------------------------------------------------------------ the reader

    /**
     * The next entry if it is due at {@code show}, removed; {@code null} if there is none or it is not due yet. A
     * {@link Row} or a {@link Marker}. The reader's.
     */
    public Object poll(long show) {
        long h = head;
        if (h == tail) {
            return null;
        }
        int i = (int) (h & mask);
        Object item = items[i];
        if (times[i] > show && markers.get() == 0) {
            return null;
        }
        if (item instanceof Marker) {
            markers.decrementAndGet();
        }
        items[i] = null;
        head = h + 1;           // hands the slot back to the writer
        return item;
    }

    /** Whether anything is waiting. The reader's. */
    public boolean isEmpty() {
        return head == tail;
    }

    @Override
    public long next(long from) {
        long h = head, t = Math.min(tail, head + SCAN);
        for (long k = h; k < t; k++) {
            int i = (int) (k & mask);
            if (cues[i] && times[i] >= from) {
                return times[i];
            }
        }
        return Long.MAX_VALUE;
    }

    @Override
    public int within(long from, long to, int limit) {
        int n = 0;
        long h = head, t = Math.min(tail, head + SCAN);
        for (long k = h; k < t && n < limit; k++) {
            int i = (int) (k & mask);
            if (times[i] > to && times[i] != Long.MIN_VALUE) {
                break;
            }
            if (cues[i] && times[i] > from) {
                n++;
            }
        }
        return n;
    }
}
