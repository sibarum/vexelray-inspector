package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.vexelray.framework.core.Timer;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the bench costs at a real run's pace (design.md section 10, R1's "measured"): a probe recording's rows, looped
 * to fill more than the tape's minute, published a 10 ms batch at a time, as the link sends them, and folded by the
 * bench on a tick of this test's. Prints the time and the allocation per batch, by the minute, and the heap the
 * bench's state holds once the tape is full.
 *
 * <p>Run on purpose, not with the suite: {@code mvn test -pl vexelray-inspector-core -Dtest=BenchMeasureTest
 * -Dmeasure.csv=<a recording> [-Dmeasure.seconds=90]}. Without {@code measure.csv} it is skipped.
 */
class BenchMeasureTest {

    private static final long MS = 1_000_000L;
    private static final long BATCH = 10 * MS;

    @Test
    void theBenchAtARecordingsPace() throws IOException {
        String csv = System.getProperty("measure.csv", "");
        assumeTrue(!csv.isEmpty(), "set -Dmeasure.csv to a probe recording to measure");
        long seconds = Long.getLong("measure.seconds", 90L);
        List<Row> recorded = read(Path.of(csv));
        long span = recorded.getLast().atNanos() - recorded.getFirst().atNanos();
        System.out.printf(Locale.ROOT, "recording: %d rows over %.1f s, %.0f rows/s%n", recorded.size(), span / 1e9,
                recorded.size() / (span / 1e9));

        long baseline = usedAfterGc();
        Atchung bus = Atchung.create();
        Tick tick = bus.tick(null);
        Timer timer = new Timer(bus);
        Bench bench = new Bench(tick.inbox("bench"), timer, "", "", "");
        Inbox heard = tick.inbox("heard");
        heard.subscribe(Messages.TIMELINE_TOPIC, t -> { }, 256);
        heard.subscribe(Messages.READOUTS_TOPIC, t -> { }, 256);
        heard.subscribe(Messages.DETAILS_TOPIC, t -> { }, 256);
        heard.subscribe(Messages.RUN_PANEL_TOPIC, t -> { }, 256);
        Run run = new Run("measured-1", "Measured", 1, "uds:none");
        bus.publish(Messages.RUNS_TOPIC, new Runs(List.of(run), ""));
        tick.run(System.nanoTime() + 60_000 * MS);

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().threadId();
        long base = recorded.getFirst().atNanos();
        long end = seconds * 1_000 * MS;
        List<Long> times = new ArrayList<>();
        long rowsInMinute = 0;
        long bytes = threads.getThreadAllocatedBytes(self);
        int i = 0;
        long loop = 0;
        List<Row> batch = new ArrayList<>();
        for (long at = BATCH; at <= end; at += BATCH) {
            batch.clear();
            while (true) {
                Row r = recorded.get(i);
                long t = r.atNanos() - base + loop * (span + MS);
                if (t > at) {
                    break;
                }
                batch.add(new Row(r.seq(), t, r.wallNanos(), r.frame(), r.thread(), r.lane(), r.kind(), r.detail(),
                        r.cause()));
                if (++i == recorded.size()) {
                    i = 0;
                    loop++;
                }
            }
            bus.publish(Messages.ROWS_TOPIC, new Rows(run.name(), 0, 0, batch));
            long before = System.nanoTime();
            tick.run(System.nanoTime() + 60_000 * MS);
            times.add(System.nanoTime() - before);
            rowsInMinute += batch.size();
            if (at % (10_000 * MS) == 0) {
                long now = threads.getThreadAllocatedBytes(self);
                long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
                System.out.printf(Locale.ROOT,
                        "to %3d s: %6d rows, a batch p50 %.2f ms p95 %.2f ms max %.2f ms, %.0f bytes allocated a row%n",
                        at / (1_000 * MS), rowsInMinute, sorted[sorted.length / 2] / 1e6,
                        sorted[(int) (sorted.length * 0.95)] / 1e6, sorted[sorted.length - 1] / 1e6,
                        (now - bytes) / (double) Math.max(1, rowsInMinute));
                bytes = now;
                times.clear();
                rowsInMinute = 0;
            }
        }
        Bench.State s = bench.state();
        long held = usedAfterGc() - baseline;
        System.out.printf(Locale.ROOT, "tape holds %.1f s of rows; kept events %d; the bench and its bus hold about %.0f MB more than before%n",
                (s.now().newestNanos() - s.tape().oldestNanos()) / 1e9,
                s.kept().lanes().stream().mapToInt(l -> l.marks().size()).sum(), held / 1e6);
        timer.close();
    }

    private static long usedAfterGc() {
        for (int k = 0; k < 3; k++) {
            System.gc();
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static List<Row> read(Path csv) throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            RecordingRows.Columns columns = RecordingRows.Columns.of(RecordingRows.split(in.readLine()));
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                Row r = columns.row(RecordingRows.split(line));
                if (r != null) {
                    out.add(r);
                }
            }
        }
        return out;
    }
}
