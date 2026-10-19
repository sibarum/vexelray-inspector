package dev.sibarum.inspector.rows;

import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.gui.inspector.link.InspectorLink;
import dev.vexelray.gui.recorder.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Launching: the command line in and out of one setting, which discovery file is the child's, and a real child
 * started, attached to over a real link, and stopped.
 */
class LaunchSourceTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    private Path dir;
    private InspectorLink link;
    private LaunchSource source;

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) {
            source.close();
        }
        if (link != null) {
            link.close();
        }
        if (dir != null) {
            try (var left = Files.list(dir)) {
                for (Path p : left.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /** What the sink heard, in order. */
    private static final class Heard implements RowSink {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void begin(String origin) {
            events.add("begin " + origin);
        }

        @Override
        public void row(Row row) {
            events.add(row.lane() + " " + row.kind() + " " + row.detail());
        }

        @Override
        public void end(String why) {
            events.add("end " + why);
        }
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        long until = System.nanoTime() + 30_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static Discovery.Entry app(String app, long pid, String stream) {
        Map<String, String> facts = new LinkedHashMap<>(Map.of("app", app, "pid", Long.toString(pid)));
        if (stream != null) {
            facts.put("stream", stream);
        }
        return new Discovery.Entry(Path.of(app + "-" + pid + ".auto"), facts);
    }

    @Test
    void aCommandLineIsWordsAndAQuotedStretchIsOne() {
        assertEquals(List.of("mvn.cmd", "compile", "exec:exec", "-Dautomation=0"),
                LaunchSource.split("  mvn.cmd compile\texec:exec  -Dautomation=0 "));
        assertEquals(List.of("C:\\Program Files\\demo\\demo.exe", "--automation=0", ""),
                LaunchSource.split("\"C:\\Program Files\\demo\\demo.exe\" --automation=0 \"\""));
    }

    @Test
    void aBareLaunchTakesTheRestOfTheLineAsOneSetting() {
        String[] folded = LaunchSource.fold(new String[] {"--zoom=2", "--launch", "C:\\Program Files\\d.exe", "-x"});
        assertArrayEquals(new String[] {"--zoom=2", "--launch=\"C:\\Program Files\\d.exe\" -x"}, folded);
        assertEquals(List.of("C:\\Program Files\\d.exe", "-x"), LaunchSource.split(folded[1].substring(9)));
        String[] untouched = {"--launch=demo.exe --automation=0", "--attach=x"};
        assertArrayEquals(untouched, LaunchSource.fold(untouched));
    }

    @Test
    void aLaunchedChildProbesTheBusUnlessItsEnvironmentSaysOtherwise() {
        Map<String, String> env = new java.util.HashMap<>();
        LaunchSource.probe(env);
        assertEquals("frame,input,layout,app,bus", env.get("PROBE"));
        assertEquals("csv", env.get("PROBE_FORMAT"));
        Map<String, String> chosen = new java.util.HashMap<>(Map.of("PROBE", "frame"));
        LaunchSource.probe(chosen);
        assertEquals(Map.of("PROBE", "frame"), chosen, "the caller's choice stands");
    }

    @Test
    void theChildIsTheOneInItsTreeThatAdvertisesALink() {
        Discovery.Entry stranger = app("demo", 10, "tcp:1");
        Discovery.Entry noLink = app("demo", 20, null);
        Discovery.Entry child = app("demo", 30, "tcp:3");
        assertEquals(child, LaunchSource.find(List.of(stranger, noLink, child), Set.of(20L, 30L)).orElseThrow());
        assertTrue(LaunchSource.find(List.of(stranger, noLink), Set.of(20L, 30L)).isEmpty(), "not yet");
    }

    @Test
    void aCommandThatCannotStartEndsTheRunWithTheReason() throws Exception {
        Heard heard = new Heard();
        source = new LaunchSource(List.of("no-such-program-anywhere"), 5, List::of);
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the end");
        assertTrue(heard.events.getFirst().startsWith("end could not launch no-such-program-anywhere: "),
                heard.events.toString());
    }

    @Test
    void aChildThatExitsFirstEndsTheRunWithItsStatus() throws Exception {
        Heard heard = new Heard();
        source = new LaunchSource(List.of(JAVA, "-version"), 30, List::of);
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the end");
        assertEquals("end " + JAVA + " exited (status 0) before it could be inspected", heard.events.getFirst());
    }

    @Test
    void aLaunchedChildIsInspectedLiveAndStoppedOnClose() throws Exception {
        dir = Files.createTempDirectory("launch");
        Recorder ring = new Recorder(64, () -> 7);
        link = InspectorLink.start(dir.resolve("a.sock"), ring, null, line -> "ok " + line);
        // A stand-in application: announces a port as the framework does, then waits to be stopped.
        Path app = dir.resolve("Standin.java");
        Files.writeString(app, """
                class Standin {
                    public static void main(String[] a) throws Exception {
                        System.out.println("automation: localhost:50845 (control)");
                        System.out.flush();
                        Thread.sleep(120_000);
                    }
                }
                """);
        // The discovery folder, as the stand-in would have written it: its own pid, the test's link. This JVM's only
        // living child is the stand-in (Windows does not say another process's command line).
        List<Long> seen = new CopyOnWriteArrayList<>();
        Heard heard = new Heard();
        source = new LaunchSource(List.of(JAVA, app.toString()), 60, () -> ProcessHandle.current().children()
                .filter(ProcessHandle::isAlive)
                .peek(p -> seen.add(p.pid()))
                .map(p -> app("standin", p.pid(), link.endpoint()))
                .toList());
        source.start(heard);
        await(() -> heard.events.stream().anyMatch(e -> e.startsWith("begin ")), "the run to begin");
        long pid = seen.getFirst();
        assertEquals("begin standin (pid " + pid + "), live", heard.events.getFirst());
        assertEquals("standin (pid " + pid + ")", source.describe(), "named once it is up");
        Thread.sleep(50);     // the stream is opened after begin
        ring.row(1, 1_000_000, 1, "main", Lane.FRAME, "frame.present", "#7");
        await(() -> heard.events.size() >= 2, "a row");
        assertEquals("frame frame.present #7", heard.events.get(1));

        source.close();
        source = null;
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        await(() -> child == null || !child.isAlive(), "the child to stop");
        assertFalse(child != null && child.isAlive());
    }
}
