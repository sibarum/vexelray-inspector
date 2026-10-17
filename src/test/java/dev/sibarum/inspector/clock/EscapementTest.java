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
