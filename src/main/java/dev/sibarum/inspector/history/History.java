package dev.sibarum.inspector.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every run the Inspector has measured, by application and build: one CSV file per application,
 * {@code <dir>/<group>/<artifact>.csv}, one {@link Summary} per line, appended as each run ends.
 *
 * <p>A file per application, not per build, so comparing builds reads one file. Appending only, so a line once
 * written is never rewritten, and an Inspector killed mid-write leaves at most a torn last line, which reading skips.
 *
 * <p>Disk work: call it on the offload lane, never on the GUI thread or a source's thread.
 */
public final class History {

    private final Path dir;

    /** A history kept under {@code dir}: {@code AppHome.of("vexelray-inspector").dir().resolve("history")}. */
    public History(Path dir) {
        this.dir = dir;
    }

    /** The file for {@code build}'s application. */
    Path file(Build build) {
        return dir.resolve(safe(build.group())).resolve(safe(build.artifact()) + ".csv");
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        return t.isEmpty() || t.equals(".") || t.equals("..") ? "_" : t;
    }

    /** Keep {@code s}. A build whose source is unknown is not kept: there is nothing to tell its runs apart by. */
    public synchronized boolean add(Summary s) throws IOException {
        if (!s.build().known()) {
            return false;
        }
        Path f = file(s.build());
        Files.createDirectories(f.getParent());
        String text = (Files.exists(f) ? "" : String.join(",", Summary.COLUMNS) + "\n") + s.line() + "\n";
        Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return true;
    }

    /** Every run kept for {@code build}'s application, oldest first. */
    public synchronized List<Summary> runs(Build build) throws IOException {
        Path f = file(build);
        List<Summary> runs = new ArrayList<>();
        if (!Files.exists(f)) {
            return runs;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            Summary s = Summary.parse(line);
            if (s != null) {
                runs.add(s);
            }
        }
        return runs;
    }

    /**
     * {@code build}'s application, build by build: the {@code limit} most recently run builds, newest first, each
     * summed over its runs. The running build is first when it has been run before.
     */
    public List<BuildStats> builds(Build build, int limit) throws IOException {
        Map<String, List<Summary>> bySource = new LinkedHashMap<>();
        for (Summary s : runs(build)) {
            bySource.remove(s.build().key());                     // re-inserted last: most recent run wins order
            bySource.computeIfAbsent(s.build().key(), k -> new ArrayList<>());
        }
        for (Summary s : runs(build)) {
            bySource.get(s.build().key()).add(s);
        }
        List<BuildStats> out = new ArrayList<>();
        List<String> order = new ArrayList<>(bySource.keySet());
        for (int i = order.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(BuildStats.of(bySource.get(order.get(i))));
        }
        return out;
    }

    /** The builds of one application, summed over their runs. */
    public record BuildStats(Build build, int runs, long frames, double seconds, long p50Us, long p95Us,
                             long worstP99Us, long stalls, long lostRows, String lastRun) {

        /** Sum the runs of one build: medians of the per-run medians and 95ths, the worst 99th, totals. */
        static BuildStats of(List<Summary> runs) {
            Summary last = runs.getLast();
            long frames = 0;
            double seconds = 0;
            long stalls = 0;
            long lost = 0;
            long worst = -1;
            long[] p50 = new long[runs.size()];
            long[] p95 = new long[runs.size()];
            int n = 0;
            for (Summary s : runs) {
                frames += s.frames();
                seconds += s.seconds();
                stalls += s.stalls();
                lost += s.lostRows();
                worst = Math.max(worst, s.p99Us());
                if (s.p50Us() >= 0) {
                    p50[n] = s.p50Us();
                    p95[n] = s.p95Us();
                    n++;
                }
            }
            return new BuildStats(last.build(), runs.size(), frames, seconds, median(p50, n), median(p95, n), worst,
                    stalls, lost, last.started());
        }

        private static long median(long[] v, int n) {
            if (n == 0) {
                return -1;
            }
            long[] s = Arrays.copyOf(v, n);
            Arrays.sort(s);
            return s[(n - 1) / 2];
        }

        /** Frames per second over every run, or -1. */
        public double fps() {
            return seconds > 0 ? frames / seconds : -1;
        }

        /** One line for people: {@code c8772f2  3 runs  p50 0.62 ms  p95 1.4 ms  p99 3.1 ms  0 stalls}. */
        public String text() {
            return String.format(Locale.ROOT, "%s  %d run%s  p50 %s  p95 %s  p99 %s  %d stall%s",
                    build.shortSource(), runs, runs == 1 ? "" : "s", ms(p50Us), ms(p95Us), ms(worstP99Us), stalls,
                    stalls == 1 ? "" : "s");
        }

        private static String ms(long us) {
            return us < 0 ? "—" : us >= 10_000 ? String.format(Locale.ROOT, "%.0f ms", us / 1000.0)
                    : String.format(Locale.ROOT, "%.2f ms", us / 1000.0);
        }
    }
}
