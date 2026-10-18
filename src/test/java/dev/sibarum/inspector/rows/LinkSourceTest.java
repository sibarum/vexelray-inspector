package dev.sibarum.inspector.rows;

import dev.vexelray.gui.inspector.link.InspectorLink;
import dev.vexelray.gui.recorder.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live source against a real link, over a real socket, with a hand-fed recorder standing in for an application:
 * what reaches the sink, in what shape, and how the run ends.
 */
class LinkSourceTest {

    private Path dir;
    private InspectorLink link;
    private LinkSource source;

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
        final List<Row> rows = new CopyOnWriteArrayList<>();

        @Override
        public void begin(String origin) {
            events.add("begin " + origin);
        }

        @Override
        public void row(Row row) {
            rows.add(row);
            events.add(row.lane() + " " + row.kind() + " " + row.detail());
        }

        @Override
        public void end(String why) {
            events.add("end " + why);
        }
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private Recorder linked() throws Exception {
        dir = Files.createTempDirectory("ls");
        Recorder ring = new Recorder(64, () -> 7);
        link = InspectorLink.start(dir.resolve("a.sock"), ring, null, line -> "ok " + line);
        return ring;
    }

    @Test
    void liveFrameRowsArriveStampedAndTheOtherLanesStayHome() throws Exception {
        Recorder ring = linked();
        Heard heard = new Heard();
        source = new LinkSource(link.endpoint(), "demo (pid 1)");
        source.start(heard);
        await(() -> heard.events.contains("begin demo (pid 1), live"), "the run to begin");
        Thread.sleep(50);     // the stream is opened after begin; let it open before rows are written
        ring.row(1, 1_000_000, 1_792_278_599_195_946_000L, "main", Lane.FRAME, "frame.present", "#7");
        ring.row(2, 2_000_000, 1_792_278_599_196_946_000L, "main", Lane.BUS, "vexelray.gui.mutations", "1");
        ring.row(3, 3_000_000, 1_792_278_599_197_946_000L, "main", Lane.FRAME, "loop.park", "forever");
        await(() -> heard.rows.size() >= 2, "two frame rows");
        Row present = heard.rows.getFirst();
        assertEquals(new Row(1, 1_000_000, "2026-10-17T23:09:59.195946Z", "main", "frame", "frame.present", "#7", 7),
                present);
        assertEquals(7, present.presentedFrame(), "the stamp, not the detail");
        assertEquals("frame loop.park forever", heard.events.get(2), "and the bus row never came");
    }

    @Test
    void lostRowsArriveAsOneRowOfTheirOwn() throws Exception {
        Recorder ring = linked();
        Heard heard = new Heard();
        source = new LinkSource(link.endpoint(), "demo (pid 1)");
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the run to begin");
        Thread.sleep(50);
        ring.row(1, 1_000_000, 1, "main", Lane.FRAME, "frame.present", "#7");
        ring.row(5, 5_000_000, 5, "main", Lane.FRAME, "frame.present", "#8");   // 2..4 never arrived
        await(() -> heard.rows.size() >= 3, "the rows and the gap");
        assertEquals("stream lost 2..4", heard.events.get(2));
    }

    @Test
    void theRunEndsWhenTheApplicationGoesAway() throws Exception {
        linked();
        Heard heard = new Heard();
        source = new LinkSource(link.endpoint(), "demo (pid 1)");
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the run to begin");
        link.close();
        link = null;
        await(() -> heard.events.getLast().startsWith("end "), "the end");
        assertEquals("end demo (pid 1) went away", heard.events.getLast());
    }

    @Test
    void anEndpointNobodyListensOnEndsTheRunWithTheReason() throws Exception {
        dir = Files.createTempDirectory("ls");
        Heard heard = new Heard();
        source = new LinkSource("uds:" + dir.resolve("nobody.sock"), "ghost (pid 2)");
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the end");
        assertTrue(heard.events.getFirst().startsWith("end could not reach ghost (pid 2): "), heard.events.toString());
    }

    @Test
    void closingTheSourceDoesNotReportAnEnd() throws Exception {
        linked();
        Heard heard = new Heard();
        source = new LinkSource(link.endpoint(), "demo (pid 1)");
        source.start(heard);
        await(() -> !heard.events.isEmpty(), "the run to begin");
        source.close();
        Thread.sleep(100);
        assertEquals(List.of("begin demo (pid 1), live"), heard.events,
                "an end the Inspector chose is not an application going away");
    }
}
