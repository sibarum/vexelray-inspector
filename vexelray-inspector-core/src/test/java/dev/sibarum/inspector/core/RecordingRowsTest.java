package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Replay;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Tick;
import sibarum.probe.Cause;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recording played back: read as atchung writes it, and sent as a run's rows at its recorded pace, on this test's
 * thread rather than a source's, with a tick of this test's to hear what it sent.
 */
class RecordingRowsTest {

    private static final long MS = 1_000_000L;

    @TempDir
    Path dir;

    @Test
    void aLineIsSplitOnCommasOutsideQuotesAndAQuotedQuoteIsOne() {
        assertEquals(List.of("3", "4213900", "Loop", "frame.present", "a, \"b\""),
                RecordingRows.split("3,4213900,Loop,frame.present,\"a, \"\"b\"\"\""));
        assertEquals(List.of("1", "", ""), RecordingRows.split("1,,"));
    }

    @Test
    void itsRowsAreSentAtTheirRecordedPaceOnTheMachinesClockWithEveryColumnByTheHeader() throws IOException {
        Path csv = dir.resolve("run.csv");
        Files.writeString(csv, String.join("\n",
                "seq,t_mono_ns,t_wall,thread,lane,kind,cause,detail",
                "1,4000000,2026-09-02T04:31:07.881972Z,main,app,cause.process,,3f2a1 pid=48213",
                "2,9000000,2026-09-02T04:31:07.886972Z,Loop,frame,frame,3f2a1.t04.29,\"4ms, then park\"",
                "3,84000000,,Loop,frame,loop.park,,16ms",
                ""), StandardCharsets.UTF_8);

        Atchung bus = Atchung.create();
        Tick tick = bus.tick(null);
        Inbox heard = tick.inbox("heard");
        List<Rows> sent = new ArrayList<>();
        heard.subscribe(Messages.ROWS_TOPIC, sent::add, 64);
        RecordingRows source = new RecordingRows(Sender.outside(bus, Messages.ROWS_TOPIC,
                Cause.source("test", Cause.Kind.IO)));

        long before = System.nanoTime();
        Detached d = source.run(new Replay("replay:run.csv", csv.toString()));
        long took = System.nanoTime() - before;
        tick.run(System.nanoTime() + 60_000_000_000L);

        assertEquals(new Detached("replay:run.csv", "the recording ended"), d);
        assertTrue(took >= 80 * MS, "the last row is 80 ms after the first, and waited for: " + took / MS + " ms");
        assertTrue(sent.size() >= 2, "the row 75 ms later is a batch of its own");
        List<Row> rows = sent.stream().flatMap(r -> r.rows().stream()).toList();
        assertTrue(sent.stream().allMatch(r -> r.run().equals("replay:run.csv")));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).atNanos() >= before && rows.get(0).atNanos() <= before + took,
                "the first row is placed when the playback started, on the machine's clock");
        assertEquals(5 * MS, rows.get(1).atNanos() - rows.get(0).atNanos(), "spaced as recorded");
        assertEquals(80 * MS, rows.get(2).atNanos() - rows.get(0).atNanos());
        Row pass = rows.get(1);
        Instant wall = Instant.parse("2026-09-02T04:31:07.886972Z");
        assertEquals(new Row(2, pass.atNanos(), wall.getEpochSecond() * 1_000_000_000L + wall.getNano(), 0L, "Loop",
                "frame", "frame",
                "4ms, then park", "3f2a1.t04.29"), pass);
        assertEquals(0L, rows.get(2).wallNanos(), "a row with no wall clock says none");
    }

    @Test
    void aFileThatIsNotARecordingSaysSo() throws IOException {
        Path csv = dir.resolve("notes.csv");
        Files.writeString(csv, "name,value\na,1\n", StandardCharsets.UTF_8);
        Atchung bus = Atchung.create();
        RecordingRows source = new RecordingRows(Sender.outside(bus, Messages.ROWS_TOPIC,
                Cause.source("test", Cause.Kind.IO)));
        assertEquals(new Detached("replay:notes.csv",
                        "it is not a probe recording: its header has no t_mono_ns or kind"),
                source.run(new Replay("replay:notes.csv", csv.toString())));
        assertTrue(source.run(new Replay("replay:gone.csv", dir.resolve("gone.csv").toString())).why()
                .startsWith("it could not be read: "));
    }
}
