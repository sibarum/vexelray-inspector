package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Account;
import dev.sibarum.inspector.core.Messages.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each clock's turn, cut into working, waiting, resting and unaccounted by its thread's rows. */
class ClockLedgerTest {

    private static final long B = 50 * Tape.SECOND;
    private static final long US = 1_000L;

    private final List<Row> rows = new ArrayList<>();

    /** A row written at {@code atUs} microseconds after B, in pass {@code pass}. */
    private void row(long atUs, long pass, String thread, String lane, String kind, String detail) {
        rows.add(new Row(rows.size() + 1, B + atUs * US, 0L, pass, thread, lane, kind, detail, ""));
    }

    /**
     * Pass 7 works 0 to 4 ms and draws from 1 to 3.5 ms, recording for 1 ms of it and waiting on the fence for half a
     * millisecond; the loop rests 4.5 to 10 ms and is woken at 9 ms; pass 8 begins at 10.5 ms and draws at 11 ms, and
     * pass 9 at 20 ms.
     */
    private void twoPassesAndTheirDraws() {
        row(2200, 7, "main", "draw", "emit canvas", "1ms");
        row(2300, 7, "main", "draw", "draw.ran", "#3 76673.t03.9");
        row(3000, 7, "main", "gpu", "wait fence", "500us");
        row(3500, 7, "main", "gpu", "present frame", "2.5ms");
        row(4000, 7, "main", "frame", "frame", "4ms");
        row(4000, 7, "main", "frame", "loop.park", "6ms");
        row(6000, 7, "main", "frame", "draw held for the ceiling", "1");
        row(9000, 7, "vexel-logic", "frame", "wake", "message on main");
        row(10000, 7, "main", "frame", "wait for events", "5.5ms");
        row(11500, 8, "main", "draw", "draw.ran", "#4");
        row(11800, 8, "main", "gpu", "present frame", "800us");
        row(12000, 8, "main", "frame", "frame", "1.5ms");
        row(21000, 9, "main", "frame", "frame", "1ms");
    }

    @Test
    void aPassIsCutIntoItsWorkItsWaitOnTheGpuItsRestAndWhatNoRowSays() {
        twoPassesAndTheirDraws();
        Account main = ClockLedger.main(rows, B + 5000 * US);
        assertEquals(new Account(Model.MAIN, "pass #7", B, B + 10_500 * US, 3_500 * US, 500 * US, 5_500 * US,
                1_000 * US, 1_000 * US, 1, 1_500 * US, ""), main,
                "the pass to the next one's start: the wait inside it counted once, as waiting, and the wake that came"
                        + " while it rested 1.5 ms before the next pass began");
    }

    @Test
    void aDrawIsItsWorkItsWaitAndItsRecordingThenRestUntilTheNextDraw() {
        twoPassesAndTheirDraws();
        Account drawing = ClockLedger.drawing(rows, B + 5000 * US);
        assertEquals(new Account(Model.DRAWING, "draw #3", B + 1_000 * US, B + 11_000 * US, 2_000 * US, 500 * US,
                7_500 * US, 0L, 1_000 * US, -1, -1L, "resting is between draws; held once for the ceiling"),
                drawing);
    }

    @Test
    void aTurnThatHasNotEndedInTheRowsReadGivesTheOneBeforeAndSaysSo() {
        twoPassesAndTheirDraws();
        Account main = ClockLedger.main(rows, B + 30_000 * US);
        assertEquals("pass #8", main.turn());
        assertEquals(B + 20_000 * US, main.toNanos());
        assertEquals("the pass before the cursor's, which had not ended in the rows read", main.note());
        assertEquals(Account.none(Model.MAIN, "no whole pass within a second of the cursor"),
                ClockLedger.main(rows, B - 1), "before the first pass");
    }

    @Test
    void theRowsNearAMomentAreReadFromItsSecondsAndALateOneFromTheNext() {
        Tape tape = Tape.EMPTY;
        Row early = new Row(1, 10 * Tape.SECOND, "main", "frame", "frame", "1ms");
        Row near = new Row(2, 12 * Tape.SECOND, "main", "frame", "frame", "1ms");
        Row late = new Row(3, 12 * Tape.SECOND + 1, "vexel-logic", "bus", "tick", "1ms");
        Row arriving = new Row(4, 13 * Tape.SECOND + 5, "main", "frame", "frame", "1ms");
        for (Row r : List.of(early, near, arriving)) {
            tape = tape.add(List.of(r), r.atNanos(), Bench.RETAIN);
        }
        tape = tape.add(List.of(late), arriving.atNanos(), Bench.RETAIN);
        assertEquals(List.of(near, late), tape.near(12 * Tape.SECOND, Tape.SECOND / 2),
                "the moment's second, and the late row kept in the one it arrived in");
    }
}
