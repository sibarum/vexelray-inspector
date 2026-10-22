package dev.sibarum.inspector.tape;

import dev.sibarum.inspector.rows.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tape: headers kept, the reach honoured, a storm past the ring counted, and the strip's bins. */
class TapeTest {

    private static final long MS = 1_000_000L;

    private static Row row(long seq, long nanos, String lane, String kind) {
        return new Row(seq, nanos, "", "main", lane, kind, "a payload the tape never keeps", Row.NO_FRAME);
    }

    @Test
    void aRowIsKeptAsItsHeader() {
        Tape tape = new Tape(5);
        tape.begin();
        tape.row(new Row(7, 42 * MS, "", "gui-main", "frame", Row.FRAME_PRESENT, "#12", Row.NO_FRAME));
        assertEquals(1, tape.written());
        assertEquals(7, tape.seq(0));
        assertEquals(42 * MS, tape.nanos(0));
        assertEquals("gui-main", tape.thread(0));
        assertEquals("frame", tape.lane(0));
        assertEquals(Row.FRAME_PRESENT, tape.kind(0));
        assertEquals(12, tape.frame(0), "a file's frame.present names its frame");
        assertTrue(tape.present(0));
        assertTrue(tape.valid(0));
    }

    @Test
    void theRingIsSizedForTheReachAtTheRate() {
        assertTrue(new Tape(10).capacity() >= 10 * Tape.RATE);
        assertTrue(new Tape(10).capacity() < 2 * 10 * Tape.RATE);
        assertEquals(Tape.MOST, new Tape(100_000).capacity(), "never past the most it holds");
    }

    @Test
    void lessThanASecondIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Tape(0));
    }

    @Test
    void aQuietRunIsHeldForItsWholeReachAndNothingGoesEarly() {
        Tape tape = new Tape(1);
        tape.begin();
        // 2 rows a millisecond for 3 s: 6,000 rows, well inside a ring sized for 20,000 a second.
        for (int i = 0; i < 6000; i++) {
            tape.row(row(i, i * MS / 2, "bus", "topic"));
        }
        assertEquals(0, tape.overwritten());
        assertEquals(6000, tape.written() - tape.first(), "a ring is a ring: it holds what fits");
        assertEquals(tape.reach(), tape.held(), "and says it reaches the full second");
    }

    @Test
    void aStormPastTheRingPushesRowsOutEarlyAndSaysSo() {
        Tape tape = new Tape(1);
        tape.begin();
        int n = tape.capacity() * 2;
        // 100,000 rows a second, five times the rate the ring is sized for.
        for (int i = 0; i < n; i++) {
            tape.row(row(i, i * 10_000L, "bus", "topic"));
        }
        assertEquals(n - tape.capacity(), tape.overwritten());
        assertTrue(tape.held() < tape.reach() / 2, "it reaches back less than its second, and says how far");
        assertFalse(tape.valid(0));
        assertTrue(tape.valid(n - 1));
    }

    @Test
    void aNewRunForgetsTheOldOne() {
        Tape tape = new Tape(5);
        tape.begin();
        tape.row(row(1, MS, "frame", "frame"));
        tape.begin();
        assertEquals(1, tape.first());
        assertFalse(tape.valid(0));
        tape.row(row(1, 2 * MS, "frame", "frame"));
        assertTrue(tape.valid(1));
    }

    @Test
    void aTimeIsFoundByItsFirstRow() {
        Tape tape = new Tape(5);
        tape.begin();
        for (int i = 0; i < 100; i++) {
            tape.row(row(i, i * 10 * MS, "frame", "frame"));
        }
        assertEquals(0, tape.at(0));
        assertEquals(5, tape.at(45 * MS));
        assertEquals(5, tape.at(50 * MS));
        assertEquals(100, tape.at(5_000 * MS));
    }

    @Test
    void lanesGetTracksInTheOrderSeenAndTheRestShareTheLast() {
        Tape tape = new Tape(5);
        tape.begin();
        for (int i = 0; i < Tape.TRACKS; i++) {
            tape.row(row(i, i, "lane" + i, "k"));
        }
        assertEquals("lane" + (Tape.TRACKS - 1), tape.trackName(Tape.TRACKS - 1), "the last track is a lane's own");
        for (int i = 0; i < Tape.TRACKS + 3; i++) {
            tape.row(row(i, i, "lane" + i, "k"));
        }
        assertEquals(Tape.TRACKS, tape.tracks());
        assertEquals("lane0", tape.trackName(0));
        assertEquals(Tape.OTHER, tape.trackName(Tape.TRACKS - 1));
        assertEquals(Tape.TRACKS - 1, tape.track(tape.written() - 1), "the last lane seen shares the last track");
    }

    @Test
    void binsCountEachTrackAndTheFrames() {
        Tape tape = new Tape(5);
        tape.begin();
        // A frame every 10 ms for 100 ms, and three bus rows in the last 10 ms.
        for (int i = 0; i < 10; i++) {
            tape.row(new Row(i, i * 10 * MS, "", "main", "frame", Row.FRAME_PRESENT, "#" + i, Row.NO_FRAME));
        }
        for (int i = 0; i < 3; i++) {
            tape.row(row(10 + i, 95 * MS + i, "bus", "topic"));
        }
        int bins = 10;
        int[] counts = new int[(Tape.TRACKS + 1) * bins];
        assertTrue(tape.bin(0, 100 * MS, Long.MAX_VALUE, bins, counts));
        for (int b = 0; b < bins; b++) {
            assertEquals(1, counts[b], "one frame-lane row a bin");
            assertEquals(1, counts[Tape.TRACKS * bins + b], "and one frame a bin");
        }
        assertEquals(3, counts[bins + 9], "the bus track's last bin");
    }

    @Test
    void aSliceStillBeingShownKeepsItsBinsAndStopsAtShowTime() {
        Tape tape = new Tape(5);
        tape.begin();
        for (int i = 0; i < 10; i++) {
            tape.row(row(i, i * 10 * MS, "bus", "topic"));
        }
        int bins = 10;
        int[] counts = new int[(Tape.TRACKS + 1) * bins];
        // Shown up to 45 ms: the rows at 0..40 ms, each in its own tenth of the whole 100 ms, and none after.
        tape.bin(0, 100 * MS, 45 * MS, bins, counts);
        for (int b = 0; b < bins; b++) {
            assertEquals(b <= 4 ? 1 : 0, counts[b], "bin " + b);
        }
    }
}
