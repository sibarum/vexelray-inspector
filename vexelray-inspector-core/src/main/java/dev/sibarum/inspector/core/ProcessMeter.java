package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Usage;
import dev.sibarum.inspector.core.ProcessMeters.Sample;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * An inspected run's process read from outside, by its pid: the CPU time it has used, how much of it is in RAM, and
 * the machine's memory. {@link LinkRows} reads one every {@link #EVERY_NANOS} while attached and puts each on the tape
 * as a row ({@link #row}), so the strip of stats reads them as of any moment ({@link #usage}) and rewinds with every
 * panel. A recording has none, and its tiles say so.
 *
 * <p>The reading itself is the platform's ({@link ProcessMeters}): on Windows its CPU times are accounted in scheduler
 * ticks (15.6 ms by default), so a second's share is good to a few percent of one core; the stack's {@code ProcessCpu}
 * (vexelray-gui's {@code docs/plans/meter.md} section 4), which is to read cycle counters, has not landed
 * (framework-notes FN-25).
 *
 * <p>Read on the link reader's thread, never on a lane: each reading is a few system calls or three small files.
 */
final class ProcessMeter implements AutoCloseable {

    /** How often a run's process is read. */
    static final long EVERY_NANOS = 250_000_000L;

    /** The lane and kind of a reading's row; its detail is {@link Sample#detail}. */
    static final String LANE = "process";
    static final String KIND = "sample";

    /** Whether {@code r} is a reading's row. */
    static boolean isSample(Row r) {
        return r.lane().equals(LANE) && r.kind().equals(KIND);
    }

    /** {@code s}, read at {@code atNanos} on the machine's clock, as a row. */
    static Row row(Sample s, long atNanos) {
        return new Row(-1, atNanos, "", LANE, KIND, s.detail());
    }

    /**
     * What the readings among {@code rows} say of the stretch from {@code fromNanos} to {@code toNanos}: the CPU used
     * from the last reading at or before its start (or else the first inside it) to the last at or before its end,
     * over the time between them; and the memory as that last one read it. {@link Usage#NONE} with no reading.
     */
    static Usage usage(List<Row> rows, long fromNanos, long toNanos) {
        Row atStart = null;
        Row firstInside = null;
        Row last = null;
        for (Row r : rows) {
            if (!isSample(r) || r.atNanos() > toNanos) {
                continue;
            }
            if (last == null || r.atNanos() > last.atNanos()) {
                last = r;
            }
            if (r.atNanos() <= fromNanos) {
                if (atStart == null || r.atNanos() > atStart.atNanos()) {
                    atStart = r;
                }
            } else if (firstInside == null || r.atNanos() < firstInside.atNanos()) {
                firstInside = r;
            }
        }
        Sample end = last == null ? null : Sample.of(last.detail()).orElse(null);
        if (end == null) {
            return Usage.NONE;
        }
        Row first = atStart != null ? atStart : firstInside;
        Sample start = Sample.of(first.detail()).orElse(null);
        long wall = last.atNanos() - first.atNanos();
        double cores = start != null && wall > 0 && end.cpuNanos() >= start.cpuNanos()
                ? (double) (end.cpuNanos() - start.cpuNanos()) / wall : -1.0;
        long used = end.memoryTotalBytes() < 0 || end.memoryAvailableBytes() < 0 ? -1L
                : end.memoryTotalBytes() - end.memoryAvailableBytes();
        return new Usage(cores, end.cores(), end.residentBytes(), used, end.memoryTotalBytes());
    }

    // ------------------------------------------------------------------ reading

    private final ProcessMeters.Reader reader;
    private final String why;

    private ProcessMeter(ProcessMeters.Reader reader, String why) {
        this.reader = reader;
        this.why = why;
    }

    /** A meter on process {@code pid}; one that reads nothing, saying why, when it cannot be read here. */
    static ProcessMeter open(long pid) {
        if (pid <= 0) {
            return new ProcessMeter(null, "the run did not say its pid");
        }
        Optional<ProcessMeters> meters = ServiceLoader.load(ProcessMeters.class).findFirst();
        if (meters.isEmpty()) {
            return new ProcessMeter(null, "this build has no reader of processes for this platform");
        }
        try {
            return new ProcessMeter(meters.get().open(pid), "");
        } catch (RuntimeException e) {
            return new ProcessMeter(null, "its process could not be opened: " + e.getMessage());
        }
    }

    /** Why nothing is read; empty when readings are. */
    String why() {
        return why;
    }

    /** A reading now; empty when nothing is read, or the process has gone. */
    Optional<Sample> read() {
        return reader == null ? Optional.empty() : reader.read();
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
    }
}
