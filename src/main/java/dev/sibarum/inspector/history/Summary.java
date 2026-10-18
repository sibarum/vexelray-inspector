package dev.sibarum.inspector.history;

import java.util.List;

/**
 * One inspected run of one build, in high-level numbers: what is kept in the history, one line each.
 *
 * <p>Times are microseconds. A statistic with nothing to measure is -1, never 0: a run that presented no frame has no
 * frame time, and saying 0 would claim a fast one.
 *
 * @param build        what was running
 * @param started      when the Inspector began reading it, ISO-8601 UTC
 * @param seconds      how long it was read, by the target's own clock
 * @param frames       frames presented
 * @param p50Us        median frame time (the {@code frame} span)
 * @param p95Us        95th percentile frame time
 * @param p99Us        99th percentile frame time
 * @param maxUs        the slowest frame
 * @param stalls       gaps no park covered ({@code Beat#STALL})
 * @param lostRows     rows the stream lost
 * @param parksForever parks with no budget: the loop slept until something woke it
 * @param parksZero    parks with a zero budget: the loop polled and did not rest
 * @param parksTimed   parks with a budget: an idle refresh or the application's pacing
 */
public record Summary(Build build, String started, double seconds, long frames, long p50Us, long p95Us, long p99Us,
                      long maxUs, long stalls, long lostRows, long parksForever, long parksZero, long parksTimed) {

    /** The columns, in order: the header row of a history file. */
    public static final List<String> COLUMNS = List.of("group", "artifact", "version", "source", "started",
            "seconds", "frames", "p50_us", "p95_us", "p99_us", "max_us", "stalls", "lost_rows", "parks_forever",
            "parks_zero", "parks_timed", "stack");

    /** Frames per second over the run, or -1 when it was too short to say. */
    public double fps() {
        return seconds > 0 ? frames / seconds : -1;
    }

    /** As one line of a history file. No field contains a comma: coordinates, a hash, a time and numbers. */
    public String line() {
        return String.join(",", build.group(), build.artifact(), build.version(), build.source(), started,
                String.format(java.util.Locale.ROOT, "%.3f", seconds), Long.toString(frames), Long.toString(p50Us),
                Long.toString(p95Us), Long.toString(p99Us), Long.toString(maxUs), Long.toString(stalls),
                Long.toString(lostRows), Long.toString(parksForever), Long.toString(parksZero),
                Long.toString(parksTimed), build.stack());
    }

    /**
     * A line written by {@link #line()}, or {@code null} for one that is not (a header, a torn last line). A line
     * from before the {@code stack} column has no stack digest, and reads as {@code -}.
     */
    public static Summary parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length < COLUMNS.size() - 1 || f[0].equals("group")) {
            return null;
        }
        try {
            String stack = f.length >= COLUMNS.size() ? f[16] : "-";
            return new Summary(new Build(f[0], f[1], f[2], f[3], stack), f[4], Double.parseDouble(f[5]),
                    Long.parseLong(f[6]), Long.parseLong(f[7]), Long.parseLong(f[8]), Long.parseLong(f[9]),
                    Long.parseLong(f[10]), Long.parseLong(f[11]), Long.parseLong(f[12]), Long.parseLong(f[13]),
                    Long.parseLong(f[14]), Long.parseLong(f[15]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
