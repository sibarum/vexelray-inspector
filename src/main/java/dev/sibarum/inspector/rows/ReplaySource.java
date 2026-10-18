package dev.sibarum.inspector.rows;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A probe CSV file, played back at the pace it was recorded at: each row is delivered when as much wall time has
 * passed since the first row as {@code t_mono_ns} says passed in the target, divided by {@code speed}.
 *
 * <p>The rows are sorted on {@code t_mono_ns} before they are played, never on {@code seq}: two threads can be
 * numbered in an order the clock disagrees with, and it is the clock that says what happened first (automation.md
 * §4). {@code seq} breaks ties.
 *
 * <p>Delivery runs on a thread of its own, {@code inspector-replay}, which spends nearly all of its time parked. A
 * replay that is behind (a slow sink) does not sleep and does not skip: it delivers what is due at once, so the
 * order is kept and the pace catches up.
 */
public final class ReplaySource implements RowSource {

    /** Between the end of one pass and the start of the next, when looping. Long enough to read as a pause. */
    static final long LOOP_PAUSE_NANOS = 1_000_000_000L;

    private final Path file;
    private final double speed;
    private final boolean loop;
    private final Pacer pacer;
    private volatile Thread thread;

    /**
     * @param speed how many times faster than recorded; 1 is the recorded pace
     * @param loop  start again from the first row after the last
     */
    public ReplaySource(Path file, double speed, boolean loop, Pacer pacer) {
        if (!(speed > 0)) {
            throw new IllegalArgumentException("a replay goes forwards at some speed: " + speed);
        }
        this.file = file;
        this.speed = speed;
        this.loop = loop;
        this.pacer = pacer;
    }

    @Override
    public void start(RowSink sink) {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        Thread t = Thread.ofPlatform().name("inspector-replay").daemon().unstarted(() -> run(sink));
        thread = t;
        t.start();
    }

    @Override
    public boolean resumes() {
        return loop;
    }

    @Override
    public String describe() {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return "replay " + name + (speed == 1 ? "" : " at " + speed + "x") + (loop ? ", looping" : "");
    }

    @Override
    public void close() {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(RowSink sink) {
        List<Row> rows;
        try {
            rows = read(file);
        } catch (IOException | RuntimeException e) {
            sink.end("could not read " + file + ": " + e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            sink.end("no rows in " + file);
            return;
        }
        do {
            sink.begin(describe());
            if (!play(rows, sink)) {
                return;
            }
            sink.end("end of " + file.getFileName());
            pacer.waitUntil(pacer.now() + LOOP_PAUSE_NANOS);
        } while (loop && !Thread.currentThread().isInterrupted());
    }

    /**
     * One pass over {@code rows}, in their order, each delivered at its recorded offset from the first. Returns
     * false if the calling thread was interrupted before the end.
     */
    boolean play(List<Row> rows, RowSink sink) {
        long origin = rows.getFirst().monoNanos();
        long start = pacer.now();
        for (Row row : rows) {
            long due = start + offset(row.monoNanos() - origin);
            if (due - pacer.now() > 0) {
                pacer.waitUntil(due);
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            sink.row(row);
        }
        return true;
    }

    /** Recorded nanos into replay nanos. */
    long offset(long recordedNanos) {
        return speed == 1 ? recordedNanos : (long) (recordedNanos / speed);
    }

    /** Every row of {@code file}, header tolerated, in time order. */
    public static List<Row> read(Path file) throws IOException {
        RowFormat format = new RowFormat();
        List<Row> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Row row = format.parse(line);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparingLong(Row::monoNanos).thenComparingLong(Row::seq));
        return rows;
    }
}
