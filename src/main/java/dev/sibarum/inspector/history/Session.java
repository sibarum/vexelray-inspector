package dev.sibarum.inspector.history;

import dev.sibarum.inspector.clock.Beat;

import java.time.Instant;
import java.util.Arrays;

/**
 * One run of one build, being measured: fed the escapement's beats, it keeps what a {@link Summary} needs and nothing
 * else, so a long session costs a fixed amount of memory beyond its frame times.
 *
 * <p>The beats already judge what matters (a stall, rows lost, the park's budget), so the history agrees with the
 * wheel by construction rather than by a second reading of the rows.
 *
 * <p>Not thread-safe: fed on the source's thread, and summed by whoever ends the run, which {@link Recorder} orders.
 */
public final class Session {

    private final Build build;
    private final String started;
    private long[] frameNanos = new long[1024];
    private int samples;
    private long frames;
    private long stalls;
    private long lostRows;
    private long firstAt = -1;
    private long lastAt = -1;
    private long parksForever;
    private long parksZero;
    private long parksTimed;

    public Session(Build build, Instant started) {
        this.build = build;
        this.started = started.toString();
    }

    /** A frame was presented. */
    public void tick(Beat b) {
        at(b);
        frames = b.frames();
        stalls = b.stalls();
        lostRows = b.lostRows();
        if (b.frameNanos() >= 0) {
            if (samples == frameNanos.length) {
                frameNanos = Arrays.copyOf(frameNanos, samples * 2);
            }
            frameNanos[samples++] = b.frameNanos();
        }
    }

    /** The loop parked. */
    public void rest(Beat b) {
        at(b);
        if (b.restsForever()) {
            parksForever++;
        } else if (b.restsNotAtAll()) {
            parksZero++;
        } else {
            parksTimed++;
        }
    }

    /** Any other beat: a wake, or rows lost. */
    public void other(Beat b) {
        at(b);
        stalls = b.stalls();
        lostRows = b.lostRows();
    }

    private void at(Beat b) {
        if (b.atNanos() > 0) {
            if (firstAt < 0) {
                firstAt = b.atNanos();
            }
            lastAt = b.atNanos();
        }
    }

    /** Whether anything was measured: a run with no frame is not worth a line. */
    public boolean measured() {
        return frames > 0;
    }

    /** The numbers so far. */
    public Summary summary() {
        long[] sorted = Arrays.copyOf(frameNanos, samples);
        Arrays.sort(sorted);
        double seconds = firstAt >= 0 && lastAt > firstAt ? (lastAt - firstAt) / 1e9 : 0;
        return new Summary(build, started, seconds, frames, us(percentile(sorted, 0.50)),
                us(percentile(sorted, 0.95)), us(percentile(sorted, 0.99)),
                us(sorted.length == 0 ? -1 : sorted[sorted.length - 1]), stalls, lostRows, parksForever, parksZero,
                parksTimed);
    }

    /** The nearest-rank percentile of {@code sorted}, or -1 when it is empty. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return -1;
        }
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static long us(long nanos) {
        return nanos < 0 ? -1 : Math.round(nanos / 1000.0);
    }
}
