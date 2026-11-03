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
        assertEquals("the pass before: the cursor's had not ended", main.note());
        assertEquals(Account.none(Model.MAIN, "no whole pass within a second of the cursor"),
                ClockLedger.main(rows, B - 1), "before the first pass");
    }

    @Test
    void aTickIsItsWorkAndTheParkBetweenTicksIsUnaccountedSinceNoRowSaysItAndOneOverItsDeadlineSaysSo() {
        row(1000, 1, "vexel-logic", "bus", "tick", "1ms");
        row(1010, 1, "vexel-logic", "bus", "tick.ran", "#5 2 rounds 3 delivered");
        row(14000, 2, "vexel-logic", "bus", "tick", "9ms");
        row(14010, 2, "vexel-logic", "bus", "tick.ran", "#6 1 rounds 1 delivered");
        row(15000, 2, "main", "bus", "tick", "100us");
        row(20500, 3, "vexel-logic", "bus", "tick", "500us");
        assertEquals(new Account(Model.LOGIC, "tick #5", B, B + 5_000 * US, 1_000 * US, -1L, -1L, 4_000 * US, -1L, -1,
                -1L, "its park writes no row (FN-17)"), ClockLedger.logic(rows, B + 2_000 * US, "vexel-logic"),
                "the widgets' tick on main is not the logic's");
        Account over = ClockLedger.logic(rows, B + 6_000 * US, "vexel-logic");
        assertEquals("tick #6", over.turn());
        assertEquals("its park writes no row (FN-17); over its 8 ms deadline", over.note());
        assertEquals("", ClockLedger.logic(rows, B + 2_000 * US, "").turn(), "no logic thread known");
    }

    @Test
    void aTickParkedUntilAMessageRestsAndTheWakeThatEndedItSaysHowLongTheNextTickTookToRun() {
        row(1000, 1, "vexel-logic", "bus", "tick", "1ms");
        row(1010, 1, "vexel-logic", "bus", "tick.ran", "#5 logic 2 rounds 3 delivered");
        row(8500, 1, "main", "bus", "wake", "message on logic");
        row(9000, 1, "vexel-logic", "bus", "logic park", "8ms");
        row(10000, 2, "vexel-logic", "bus", "tick", "500us");
        row(20500, 3, "vexel-logic", "bus", "tick", "500us");
        assertEquals(new Account(Model.LOGIC, "tick #5", B, B + 9_500 * US, 1_000 * US, -1L, 8_000 * US, 500 * US,
                -1L, 1, 1_000 * US, ""), ClockLedger.logic(rows, B + 2_000 * US, "vexel-logic"),
                "its park its rest, the half millisecond after it unsaid, and the wake 1 ms before the next tick began");
    }

    @Test
    void underLogicOnMainTheTicksAreTheOnesNamedTheLogicsAndTheRestOfTheTurnIsTheMainThreads() {
        row(1000, 1, "main", "bus", "tick", "100us");
        row(1010, 1, "main", "bus", "tick.ran", "#1 1 rounds 1 delivered");
        row(2000, 1, "main", "bus", "tick", "1ms");
        row(2010, 1, "main", "bus", "tick.ran", "#1 logic 2 rounds 3 delivered");
        row(5000, 2, "main", "bus", "tick", "100us");
        row(5010, 2, "main", "bus", "tick.ran", "#2 1 rounds 1 delivered");
        row(9000, 3, "main", "bus", "tick", "500us");
        row(9010, 3, "main", "bus", "tick.ran", "#2 logic 1 rounds 1 delivered");
        Account a = ClockLedger.logic(rows, B + 3_000 * US, "main");
        assertEquals("tick #1", a.turn(), "the logic's, not the widgets' tick at 5 ms");
        assertEquals(B + 1_000 * US, a.fromNanos());
        assertEquals(B + 8_500 * US, a.toNanos());
        assertEquals("on the main thread: the rest of its turn is the main thread's", a.note());

        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        assertEquals(List.of("#1", "#2"), m.lane(Model.LOGIC).marks().stream().map(k -> k.field(Model.TICK_NUMBER))
                .toList(), "the logic lane holds the logic's ticks on main, and not the widgets'");
        assertEquals("2", m.lane(Model.LOGIC).marks().getFirst().field(Model.ROUNDS), "its figures read past its name");
        Model split = Model.EMPTY.fold(rows.subList(0, 3), Long.MAX_VALUE).fold(rows.subList(3, rows.size()),
                Long.MAX_VALUE);
        assertEquals(m.lane(Model.LOGIC), split.lane(Model.LOGIC), "a zone and its row in two batches, the same");
    }

    @Test
    void theLedgerHasEveryClockAndJobsSayTheyWriteNoRows() {
        twoPassesAndTheirDraws();
        Messages.LedgerAt l = ClockLedger.at("run", rows, B + 5000 * US, "");
        assertEquals(ClockLedger.CLOCKS, l.accounts().stream().map(Account::clock).toList());
        assertEquals(Account.none(ClockLedger.JOBS, "a job writes no rows (FN-15)"), l.accounts().getLast());
    }

    @Test
    void followingReadsOnlyTheNewestRowsTheLedgerReadsBackToAMomentAndALittleLateness() {
        Tape tape = Tape.EMPTY;
        Row old = new Row(1, 10 * Tape.SECOND, "main", "frame", "frame", "1ms");
        Row send = new Row(2, 10 * Tape.SECOND + 400 * US * 1000, "vexel-logic", "bus", "send", "Rows from Bench 1");
        Row pass = new Row(3, 10 * Tape.SECOND + 500 * US * 1000, "main", "frame", "frame", "1ms");
        Row late = new Row(4, 10 * Tape.SECOND + 450 * US * 1000, "vexel-logic", "bus", "tick", "1ms");
        Row newest = new Row(5, 10 * Tape.SECOND + 600 * US * 1000, "main", "gpu", "present frame", "1ms");
        tape = tape.add(List.of(old, send, pass, late, newest), newest.atNanos(), Bench.RETAIN);
        assertEquals(List.of(newest, late, pass), tape.since(10 * Tape.SECOND + 420 * US * 1000, ClockLedger::reads),
                "newest first, the late tick among them; not the send, which the ledger does not read, nor the old pass");
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
        assertEquals(List.of(near, late), tape.near(12 * Tape.SECOND, Tape.SECOND / 2, r -> true),
                "the moment's second, and the late row kept in the one it arrived in");
    }
}
