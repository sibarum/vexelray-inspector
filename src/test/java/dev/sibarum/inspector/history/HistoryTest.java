package dev.sibarum.inspector.history;

import dev.sibarum.inspector.clock.Beat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history by build: what a session measures from the beats, what a line holds, how runs of one build are summed,
 * and when a run is kept at all.
 */
class HistoryTest {

    private static final Build A = new Build("dev.example", "demo", "0.1.0-SNAPSHOT", "aaaaaaa1111", "5a5a5a5a5a5a");
    private static final Build B = new Build("dev.example", "demo", "0.1.0-SNAPSHOT", "bbbbbbb2222+dirty.0a1b2c3d4e5f", "5a5a5a5a5a5a");
    private static final Build UNKNOWN = new Build("-", "-", "-", "unknown", "-");

    /** A frame of {@code frameNanos}, the {@code n}th of the run, at {@code atNanos}. */
    private static Beat frame(long n, long frameNanos, long atNanos, long stalls) {
        return new Beat(n, n, frameNanos, -1, false, "", -1, "", "", atNanos, 0, -1, stalls);
    }

    private static Beat park(String budget, long atNanos) {
        return new Beat(0, -1, -1, -1, true, budget, -1, "", "", atNanos, 0, -1, 0);
    }

    private static Summary run(Build build, String started, long... frameMicros) {
        Session s = new Session(build, Instant.parse(started));
        long at = 1_000_000_000L;
        for (int i = 0; i < frameMicros.length; i++) {
            s.tick(frame(i + 1, frameMicros[i] * 1000, at, 0));
            at += 16_000_000L;
        }
        return s.summary();
    }

    @Test
    void aSessionMeasuresFramesParksAndTheRunsLengthFromTheBeats() {
        Session s = new Session(A, Instant.parse("2026-10-18T20:00:00Z"));
        for (int i = 1; i <= 100; i++) {
            s.tick(frame(i, i * 1_000_000L, 1_000_000_000L + i * 10_000_000L, i >= 50 ? 1 : 0));
        }
        s.rest(park("forever", 2_100_000_000L));
        s.rest(park("0ms", 2_200_000_000L));
        s.rest(park("200ms", 2_300_000_000L));
        s.rest(park("200ms", 2_400_000_000L));
        Summary sum = s.summary();
        assertEquals(100, sum.frames());
        assertEquals(50_000, sum.p50Us(), "nearest rank: the 50th of 100 frames of 1..100 ms");
        assertEquals(95_000, sum.p95Us());
        assertEquals(99_000, sum.p99Us());
        assertEquals(100_000, sum.maxUs());
        assertEquals(1, sum.stalls());
        assertEquals(1.39, sum.seconds(), 1e-9, "from the first beat to the last, by the target's clock");
        assertEquals(1, sum.parksForever());
        assertEquals(1, sum.parksZero());
        assertEquals(2, sum.parksTimed());
        assertTrue(s.measured());
    }

    @Test
    void aRunWithNoFrameHasNoFrameTimeRatherThanAFastOne() {
        Session s = new Session(A, Instant.parse("2026-10-18T20:00:00Z"));
        s.rest(park("forever", 1));
        assertFalse(s.measured());
        assertEquals(-1, s.summary().p50Us());
    }

    @Test
    void aLineRoundTripsAndAHeaderOrATornLineIsNotARun() {
        Summary s = run(A, "2026-10-18T20:00:00Z", 600, 700, 800);
        assertEquals(s, Summary.parse(s.line()));
        assertNull(Summary.parse(String.join(",", Summary.COLUMNS)));
        assertNull(Summary.parse("dev.example,demo,0.1.0-SNAPSHOT,aaaa,2026"));
    }

    @Test
    void runsAreKeptPerApplicationAndSummedPerBuildNewestFirst(@TempDir Path dir) throws Exception {
        History h = new History(dir);
        assertTrue(h.add(run(A, "2026-10-18T20:00:00Z", 500, 600, 700)));
        assertTrue(h.add(run(B, "2026-10-18T20:05:00Z", 900, 1000, 1100)));
        assertTrue(h.add(run(A, "2026-10-18T20:10:00Z", 400, 500, 600)));
        Path file = dir.resolve("dev.example").resolve("demo.csv");
        assertEquals(String.join(",", Summary.COLUMNS), Files.readAllLines(file).getFirst(), "one header");
        assertEquals(4, Files.readAllLines(file).size());

        List<History.BuildStats> builds = h.builds(A, 5);
        assertEquals(List.of("aaaaaaa1111", "bbbbbbb2222+dirty.0a1b2c3d4e5f"),
                builds.stream().map(b -> b.build().source()).toList(), "A ran last, so A first");
        History.BuildStats a = builds.getFirst();
        assertEquals(2, a.runs());
        assertEquals(6, a.frames());
        assertEquals(500, a.p50Us(), "the median of the runs' medians, 600 and 500");
        assertEquals(700, a.worstP99Us());
        assertEquals("aaaaaaa · stack 5a5a  2 runs  p50 0.50 ms  p95 0.60 ms  p99 0.70 ms  0 stalls", a.text(),
                "medians of the runs' p50s (600, 500) and p95s (700, 600), the lower of two; the worst p99");
        assertEquals("bbbbbbb+dirty · stack 5a5a  1 run  p50 1.00 ms  p95 1.10 ms  p99 1.10 ms  0 stalls", builds.get(1).text());
        assertEquals(1, h.builds(A, 1).size(), "the limit holds");
    }

    @Test
    void aBuildWhoseSourceIsUnknownIsNotKept(@TempDir Path dir) throws Exception {
        History h = new History(dir);
        assertFalse(h.add(run(UNKNOWN, "2026-10-18T20:00:00Z", 500)));
        try (var left = Files.list(dir)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void theRecorderKeepsARunWhenItEndsAndShowsTheApplicationsBuilds(@TempDir Path dir) throws Exception {
        History h = new History(dir);
        List<String> shown = new ArrayList<>();
        Recorder r = new Recorder(h, Runnable::run, (build, builds) ->
                shown.add((build == null ? "-" : build.shortSource()) + " " + builds.size()));
        r.begin(A);
        r.tick(frame(1, 600_000, 1_000_000_000L, 0));
        r.tick(frame(2, 700_000, 1_016_000_000L, 0));
        r.begin(B);                                    // switching ends A's run, and A is kept
        r.tick(frame(1, 900_000, 5_000_000_000L, 0));
        r.close();                                     // the Inspector closing keeps B's, on this thread
        assertEquals(2, h.runs(A).size());
        assertEquals(List.of("aaaaaaa · stack 5a5a 1"), shown, "after A's run was kept; close writes but reads nothing back");
        r.look(B);
        assertEquals("bbbbbbb+dirty · stack 5a5a 2", shown.getLast());
    }

    @Test
    void anUnknownBuildIsShownButNotMeasured(@TempDir Path dir) throws Exception {
        History h = new History(dir);
        List<Integer> shown = new ArrayList<>();
        Recorder r = new Recorder(h, Runnable::run, (build, builds) -> shown.add(builds.size()));
        r.begin(UNKNOWN);
        r.tick(frame(1, 600_000, 1, 0));
        assertNull(r.current());
        r.finish();
        r.begin(null);
        r.finish();
        r.look(null);
        assertEquals(List.of(0), shown);
        assertEquals(Map.of(), Map.of());
    }

    @Test
    void aStackChangeUnderTheSameApplicationCommitIsAnotherBuild(@TempDir Path dir) throws Exception {
        Build sameAppNewStack = new Build(A.group(), A.artifact(), A.version(), A.source(), "6b6b6b6b6b6b");
        History h = new History(dir);
        h.add(run(A, "2026-10-18T20:00:00Z", 500));
        h.add(run(sameAppNewStack, "2026-10-18T20:05:00Z", 900));
        List<History.BuildStats> builds = h.builds(A, 5);
        assertEquals(2, builds.size(), "same application commit, different stack: two builds, not one");
        assertEquals("aaaaaaa · stack 6b6b  1 run  p50 0.90 ms  p95 0.90 ms  p99 0.90 ms  0 stalls",
                builds.getFirst().text());
    }

    @Test
    void aStackDigestCoversEveryPartTheDiscoveryFileLists() {
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>(java.util.Map.of(
                "app.group", "dev.example", "app.artifact", "demo", "app.version", "1", "app.source", "c0ffee"));
        facts.put("stack.count", "2");
        facts.put("stack.1", "dev.a:x:1@c1");
        facts.put("stack.2", "dev.b:y:1@c2");
        Build one = Build.of(new dev.vexelray.gui.automation.Discovery.Entry(Path.of("demo-1.auto"), facts));
        facts.put("stack.2", "dev.b:y:1@c3");
        Build other = Build.of(new dev.vexelray.gui.automation.Discovery.Entry(Path.of("demo-1.auto"), facts));
        assertEquals("c0ffee", one.source());
        assertEquals(12, one.stack().length());
        assertFalse(one.key().equals(other.key()), "one stamped part moved: a different build");
        facts.put("stack.count", "0");
        assertEquals("-", Build.of(new dev.vexelray.gui.automation.Discovery.Entry(Path.of("demo-1.auto"), facts))
                .stack());
    }

    @Test
    void aLineFromBeforeTheStackColumnStillReads() {
        String old = "dev.example,demo,0.1.0-SNAPSHOT,c8772f2,2026-10-18T20:46:18Z,13.807,35,2790,4450,7720,7720,0,0,33,0,2";
        Summary s = Summary.parse(old);
        assertEquals("-", s.build().stack());
        assertEquals(35, s.frames());
    }

    @Test
    void theShortSourceIsTheCommitsHeadAndWhetherItWasDirty() {
        assertEquals("aaaaaaa · stack 5a5a", A.shortSource());
        assertEquals("bbbbbbb+dirty · stack 5a5a", B.shortSource());
        assertEquals("aaaaaaa", new Build("g", "a", "v", "aaaaaaa1111", "-").shortSource());
        assertFalse(UNKNOWN.known());
    }
}
