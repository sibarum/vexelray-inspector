package dev.sibarum.inspector.clock;

/**
 * Bursts: stretches of the bus faster than the fastest animation, counted message by message as they arrive, in
 * real time, never stretched. Beads show a burst slowly enough to follow; this says how big it really was.
 *
 * <p>A burst begins when {@code k} messages fall within {@code window}, which is the show clock's cap
 * ({@code k / window} a second), and it ends once {@code window} passes with no such stretch. Its count includes the
 * {@code k} that began it. The <b>record</b> is the biggest burst in this run; a burst that beats a record of at
 * least {@link #SHOWN} is a <b>new record</b>.
 *
 * <p>Written by the source's thread ({@link #event}), read by the timeline: each value is published on its own,
 * which is all a counter on screen needs.
 */
public final class Combo {

    /** The smallest burst worth a counter on screen. */
    public static final long SHOWN = 10;

    private final int k;
    private final long window;
    private final long[] recent;
    private int seen;
    private int next;

    private volatile boolean bursting;
    private volatile long count;
    private volatile long lastDense;
    private volatile long record;
    private volatile long recordBefore;
    private volatile long bursts;

    /** @param k messages within {@code windowNs} that make a burst */
    public Combo(int k, long windowNs) {
        if (k < 2 || windowNs <= 0) {
            throw new IllegalArgumentException("k " + k + ", window " + windowNs);
        }
        this.k = k;
        this.window = windowNs;
        this.recent = new long[k];
    }

    /** The Inspector's: 6 within 50 ms, which is the show clock's 120 a second. */
    public static Combo standard() {
        return new Combo(6, 50_000_000L);
    }

    /** A new run: no burst, no record. */
    public synchronized void reset() {
        seen = 0;
        next = 0;
        bursting = false;
        count = 0;
        lastDense = 0;
        record = 0;
        recordBefore = 0;
        bursts = 0;
    }

    /** A message was published at {@code t}, the target's time. In time order. */
    public synchronized void event(long t) {
        recent[next] = t;
        next = (next + 1) % k;
        seen = Math.min(k, seen + 1);
        boolean dense = seen == k && t - recent[next] <= window;     // recent[next] is now the oldest of the last k
        if (bursting && t - lastDense > window) {
            bursting = false;
        }
        if (dense) {
            if (!bursting) {
                recordBefore = record;
                count = k;
                bursts++;
                bursting = true;
            } else {
                count++;
            }
            lastDense = t;
        } else if (bursting) {
            count++;
        }
        if (bursting && count > record) {
            record = count;
        }
    }

    /** Whether a burst is going on at {@code now}, the target's time. */
    public boolean active(long now) {
        return bursting && now - lastDense <= window;
    }

    /** The current burst's count, or the last one's once it has ended. */
    public long count() {
        return count;
    }

    /** Bursts begun in this run; a new one is how a view knows to start its counter over. */
    public long bursts() {
        return bursts;
    }

    /** The biggest burst in this run. */
    public long record() {
        return record;
    }

    /** The current or last burst beat a record worth having. */
    public boolean newRecord() {
        return recordBefore >= SHOWN && count > recordBefore;
    }
}
