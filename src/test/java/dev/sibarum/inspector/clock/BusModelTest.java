package dev.sibarum.inspector.clock;

import dev.sibarum.inspector.rows.Row;
import dev.sibarum.inspector.rows.RowFormat;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bus's rows read, the ring between source and timeline, and the bead pool. */
class BusModelTest {

    private static final long MS = 1_000_000L;

    private static Row bus(long seq, long t, String kind, String detail) {
        return new Row(seq, t, "-", "main", "bus", kind, detail, Row.NO_FRAME);
    }

    @Test
    void aPublishIsATopicAndTheRestAreAboutOne() {
        assertTrue(BusRows.publish(bus(1, 0, "vexelray.gui.mutations", "18.1us")));
        assertFalse(BusRows.publish(bus(2, 0, "vexelray.gui.mutations depth", "3")));
        assertFalse(BusRows.publish(bus(3, 0, "pump drain", "1.81ms")));
        assertFalse(BusRows.publish(new Row(4, 0, "-", "main", "frame", "frame.present", "#1", 1)));
        assertEquals("vexelray.gui.mutations", BusRows.topic(bus(5, 0, "vexelray.gui.mutations depth", "3")));
        assertEquals(3, BusRows.count(bus(5, 0, "vexelray.gui.mutations depth", "3")));
        assertTrue(BusRows.drain(bus(6, 0, "pump drain", "19.2us")));
    }

    @Test
    void theRingReleasesWhatShowHasReachedAndFlushesARunThatIsOver() {
        Pending p = new Pending(16);
        p.row(bus(1, 10 * MS, "t", "1us"));
        p.row(bus(2, 30 * MS, "t", "1us"));
        assertEquals(10 * MS, p.next(0));
        assertEquals(2, p.within(0, 30 * MS, 99));
        assertEquals(1, p.within(0, 20 * MS, 99));
        assertNull(p.poll(5 * MS), "not yet");
        assertEquals(1, ((Row) p.poll(10 * MS)).seq());
        assertNull(p.poll(20 * MS));
        p.marker(new Pending.Marker(false, "gone"));
        assertEquals(2, ((Row) p.poll(0)).seq(), "flushed: its run has ended");
        assertInstanceOf(Pending.Marker.class, p.poll(0));
        assertTrue(p.isEmpty());
    }

    @Test
    void aFullRingDropsAndSaysWhichRowsItLost() {
        Pending p = new Pending(8);
        int room = 8;
        for (int i = 1; i <= room + 3; i++) {
            p.row(bus(i, i * MS, "t", "1us"));
        }
        assertEquals(3, p.dropped());
        for (int i = 0; i < 4; i++) {
            p.poll(Long.MAX_VALUE);
        }
        p.row(bus(20, 20 * MS, "t", "1us"));
        Object o;
        Row lost = null;
        while ((o = p.poll(Long.MAX_VALUE)) != null) {
            if (o instanceof Row r && r.lane().equals("stream")) {
                lost = r;
            }
        }
        assertEquals("9..11", lost.detail(), "the range, ahead of the next row that fit");
    }

    @Test
    void aFullPoolGivesTheOldestBeadToTheNewestMessage() {
        BeadPool pool = new BeadPool(2, 100 * MS, 3, 0);
        int a = pool.spawn(0, true, 0);
        int b = pool.spawn(1, false, 10 * MS);
        int c = pool.spawn(2, true, 20 * MS);
        assertEquals(a, c, "the oldest gave way");
        assertEquals(1, pool.retiredEarly());
        assertEquals(2, pool.count());
        assertEquals(0.5, pool.progress(b, 60 * MS), 1e-9);
        assertEquals(1, pool.progress(b, 200 * MS), 1e-9);
        assertFalse(pool.live(b), "arrived, and retired");
        assertEquals(1, pool.count());
    }

    @Test
    void aMessageCloserThanTheGapRidesOnTheBeadBeforeIt() {
        BeadPool pool = new BeadPool(8, 100 * MS, 2, 10 * MS);
        int a = pool.spawn(0, true, 0);
        assertEquals(a, pool.spawn(0, true, 5 * MS), "within the gap: the same bead");
        assertEquals(2, pool.weight(a));
        int b = pool.spawn(1, false, 6 * MS);
        assertTrue(a != b, "another belt has its own beads");
        int c = pool.spawn(0, true, 12 * MS);
        assertTrue(c != a, "past the gap: a bead of its own");
        assertEquals(1, pool.weight(c));
        assertEquals(3, pool.count());
    }

    @Test
    void theCalculatorSampleIsShownCompletelyAndCurrent() throws Exception {
        // The recorded run, through the ring and the show clock as the Inspector plays it: every publish is shown,
        // and the lag never passes twice the slack.
        RowFormat format = new RowFormat();
        List<Row> rows = Files.readAllLines(Path.of("samples/calculator.csv")).stream().map(format::parse)
                .filter(java.util.Objects::nonNull).sorted(java.util.Comparator.comparingLong(Row::monoNanos)).toList();
        long publishes = rows.stream().filter(BusRows::publish).count();
        assertTrue(publishes > 50, "the sample has a bus: " + publishes);
        Pending pending = new Pending(1 << 16);
        ShowClock clock = ShowClock.standard();
        long frame = 6_944_444L, t0 = rows.getFirst().monoNanos(), end = rows.getLast().monoNanos();
        int next = 0;
        long shown = 0, maxLag = 0;
        for (long now = t0; now <= end + 500 * MS; now += frame) {
            while (next < rows.size() && rows.get(next).monoNanos() <= now) {
                pending.row(rows.get(next++));
            }
            long show = clock.advance(frame, now, pending);
            Object o;
            while ((o = pending.poll(show)) != null) {
                if (o instanceof Row r && BusRows.publish(r)) {
                    shown++;
                }
            }
            maxLag = Math.max(maxLag, clock.lag(now));
        }
        assertEquals(publishes, shown);
        assertTrue(maxLag <= 2 * clock.slack(), "lag " + maxLag / MS + " ms");
    }
}
