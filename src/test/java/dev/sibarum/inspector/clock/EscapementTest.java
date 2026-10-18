package dev.sibarum.inspector.clock;

import dev.sibarum.inspector.rows.ReplaySource;
import dev.sibarum.inspector.rows.Row;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscapementTest {

    /** Each event, as a line. */
    static final class Log implements Escapement.Listener {
        final List<String> events = new ArrayList<>();
        final List<Beat> beats = new ArrayList<>();

        @Override
        public void tick(Beat b) {
            events.add("tick #" + b.frame() + " " + Durations.text(b.frameNanos()));
            beats.add(b);
        }

        @Override
        public void rest(Beat b) {
            events.add("rest " + b.budget());
            beats.add(b);
        }

        @Override
        public void wake(Beat b) {
            events.add("wake " + b.wake() + " after " + Durations.text(b.restedNanos()));
            beats.add(b);
        }

        @Override
        public void lost(Beat b) {
            events.add("lost " + b.lostRows());
            beats.add(b);
        }

        @Override
        public void source(String state) {
            events.add("source " + state);
        }
    }

    private static int seq;

    private static Row row(long ms10, String thread, String lane, String kind, String detail) {
        return new Row(++seq, ms10 * 100_000, "w", thread, lane, kind, detail, Row.NO_FRAME);
    }

    @Test
    void aFrameTicksAParkRestsAndAWakeSaysWhoEndedIt() {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.begin("test");
        e.row(row(0, "main", "frame", "frame", "2.39ms"));
        e.row(row(1, "main", "frame", "frame.present", "#7"));
        e.row(row(2, "main", "frame", "loop.park", "200ms"));
        e.row(row(3, "main", "bus", "vexelray.gui.mutations", "x"));        // another lane: not the wheel's
        e.row(row(500, "vexelray-caret-blink", "frame", "wake.post", "nudging"));  // how, not that
        e.row(row(502, "vexelray-caret-blink", "frame", "wake", "mutation on Gui@7823a2f9"));
        e.row(row(510, "main", "frame", "frame", "777.4us"));
        e.row(row(511, "main", "frame", "frame.present", "#8"));

        assertEquals(List.of("source test", "tick #7 2.39 ms", "rest 200ms", "wake mutation after 50.0 ms",
                "tick #8 777 µs"), log.events);

        Beat last = e.beat();
        assertEquals(2, last.frames());
        assertEquals(8, last.frame());
        assertEquals(51_000_000, last.intervalNanos());
        assertFalse(last.resting());
        assertEquals("vexelray-caret-blink", last.waker());
        assertEquals(50_000_000, last.restedNanos());
    }

    @Test
    void aRestThatRunsOutOfBudgetSaysSo() {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(1, "main", "frame", "loop.park", "200ms"));
        e.row(row(2001, "main", "frame", "before frame", "1.0ms"));
        assertEquals("wake budget spent after 200 ms", log.events.get(2));
    }

    @Test
    void lostRowsAreCountedAndTheNextFrameSaysItMissedBeats() {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(10, "main", "frame", "frame.present", "#2"));
        assertFalse(e.beat().missedBeats());
        e.row(row(20, "-", "stream", "lost", "5..9"));
        assertEquals(5, e.beat().lostRows());
        assertFalse(e.beat().missedBeats(), "not until a frame comes after the gap");
        e.row(row(90, "main", "frame", "frame.present", "#9"));

        assertEquals(List.of("tick #1 —", "tick #2 —", "lost 5", "tick #9 —"), log.events);
        Beat after = e.beat();
        assertEquals(-1, after.intervalNanos(), "an interval across a gap would be made up");
        assertTrue(after.missedBeats());
        assertEquals(5, after.lostRows());

        e.row(row(100, "main", "frame", "frame.present", "#10"));
        assertFalse(e.beat().missedBeats(), "the frame after that is measured again");
        assertEquals(1_000_000, e.beat().intervalNanos());
        e.row(row(110, "-", "stream", "lost", "12..12"));
        assertEquals(6, e.beat().lostRows(), "gaps add up over a run");
    }

    @Test
    void aGapDuringAParkEndsTheRestWithoutMeasuringIt() {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(1, "main", "frame", "loop.park", "200ms"));
        e.row(row(50, "-", "stream", "lost", "3..40"));
        assertFalse(e.beat().resting(), "the park's end may be among the lost rows");
        assertEquals(-1, e.beat().restedNanos());
        e.row(row(60, "main", "frame", "wake", "mutation on Gui@1"));
        assertEquals(List.of("tick #1 —", "rest 200ms", "lost 38"), log.events, "no rest to end, so no wake");
    }

    @Test
    void aSilenceWithNoParkInItIsAStall() {
        Escapement e = new Escapement(new Log());
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(5000, "main", "frame", "frame.present", "#2"));       // 500 ms, and nothing said it would sleep
        assertTrue(e.beat().stalled());
        assertEquals(500_000_000, e.beat().uncoveredNanos());
        assertEquals(1, e.beat().stalls());
        e.row(row(5160, "main", "frame", "frame.present", "#3"));       // 16 ms: a frame, not a stall
        assertFalse(e.beat().stalled());
        assertEquals(1, e.beat().stalls(), "stalls add up over a run");
    }

    @Test
    void aRestThatEndsInTimeClearsTheStallBeforeIt() {
        Escapement e = new Escapement(new Log());
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(5000, "main", "frame", "frame.present", "#2"));
        e.row(row(5001, "main", "frame", "loop.park", "forever"));
        e.row(row(9001, "main", "frame", "wake", "injected input on Gui@1"));
        assertFalse(e.beat().stalled(), "the stall was the gap before #2, not this rest");
        assertEquals(1, e.beat().stalls());
    }

    @Test
    void aRestThatOutlastsItsBudgetIsAStallAsSoonAsItEnds() {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(1, "main", "frame", "loop.park", "200ms"));
        e.row(row(15001, "main", "frame", "before frame", "1.0ms"));     // 1.5 s on a 200 ms budget
        assertTrue(e.beat().stalled(), "said at the wake, before the next frame");
        assertEquals(1_300_000_000, e.beat().uncoveredNanos());
        assertEquals(1, e.beat().stalls());
        e.row(row(15011, "main", "frame", "frame.present", "#2"));
        assertTrue(e.beat().stalled());
        assertEquals(1, e.beat().stalls(), "one gap, one stall, though it was judged twice");
        assertEquals(1_301_100_000, e.beat().uncoveredNanos(), "frame to frame, less the budget");
    }

    @Test
    void aParkThatCoversTheGapIsNotAStall() {
        Escapement e = new Escapement(new Log());
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(1, "main", "frame", "loop.park", "200ms"));
        e.row(row(2601, "main", "frame", "frame.present", "#2"));       // 60 ms past the budget: under STALL
        assertFalse(e.beat().stalled());
        assertEquals(60_100_000, e.beat().uncoveredNanos());
        e.row(row(2602, "main", "frame", "loop.park", "forever"));
        e.row(row(302602, "main", "frame", "wake", "injected input on Gui@1"));  // 30 s: an unfocused nap
        e.row(row(302610, "main", "frame", "frame.present", "#3"));
        assertFalse(e.beat().stalled());
        assertEquals(0, e.beat().stalls());
    }

    @Test
    void aGapTheStreamLostIsNeverAStall() {
        Escapement e = new Escapement(new Log());
        e.row(row(0, "main", "frame", "frame.present", "#1"));
        e.row(row(1, "main", "frame", "loop.park", "0ms"));
        e.row(row(100, "-", "stream", "lost", "3..900"));
        e.row(row(20000, "main", "frame", "frame.present", "#300"));    // 2 s, but the rows inside it are gone
        assertFalse(e.beat().stalled());
        assertTrue(e.beat().missedBeats());
        assertEquals(-1, e.beat().uncoveredNanos());
        assertEquals(0, e.beat().stalls());
    }

    @Test
    void aLostRangeCountsItsRows() {
        assertEquals(3, Escapement.count("2..4"));
        assertEquals(1, Escapement.count("7..7"));
        assertEquals(1, Escapement.count("garbled"), "a gap of unknown size is still a gap");
    }

    @Test
    void beginningAgainForgetsTheLastRun() {
        Escapement e = new Escapement(new Log());
        e.row(row(0, "main", "frame", "frame.present", "#41"));
        e.begin("again");
        assertEquals(Beat.NONE, e.beat());
    }

    @Test
    void theSampleTicksOncePerPresentAndEndsAtRestForever() throws Exception {
        Log log = new Log();
        Escapement e = new Escapement(log);
        e.begin("sample");
        for (Row r : ReplaySource.read(Path.of("samples/calculator.csv"))) {
            e.row(r);
        }
        assertEquals(43, e.beat().frames());
        assertEquals(43, e.beat().frame());
        assertTrue(e.beat().resting());
        assertTrue(e.beat().restsForever());
        assertEquals(0, e.beat().stalls(), "its slow startup frames are slow frames, not stalls");
        assertTrue(log.events.stream().anyMatch(s -> s.startsWith("wake mutation")), "a caret blink wakes it");
        assertTrue(log.events.stream().anyMatch(s -> s.startsWith("wake injected input")), "and so does a key");
    }

    @Test
    void durationsReadProbesUnitsAndBareBudgets() {
        assertEquals(41_320_000, Durations.nanos("41.32ms"));
        assertEquals(777_400, Durations.nanos("777.4us"));
        assertEquals(850, Durations.nanos("850ns"));
        assertEquals(1_200_000_000, Durations.nanos("1.2s"));
        assertEquals(200_000_000, Durations.nanos("200"));
        assertEquals(Long.MAX_VALUE, Durations.nanos("forever"));
        assertEquals(-1, Durations.nanos("soon"));
        assertEquals("—", Durations.text(-1));
        assertEquals("41.3 ms", Durations.text(41_320_000));
        assertEquals("1.20 s", Durations.text(1_200_000_000));
    }
}
