package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The tape's seconds, and which of them a cause's rows are read from. */
class TapeTest {

    private static final long S = Tape.SECOND;

    private static Row row(long seq, long at, String cause) {
        return new Row(seq, at, 0L, 1L, "vexel-logic", "bus", "send", "Rows from Bench 1", cause);
    }

    @Test
    void aCausesRowsAreReadFromTheSecondsItIsInAndAddedABatchAtATime() {
        Tape tape = Tape.EMPTY;
        List<Row> rows = List.of(
                row(1, 10 * S, "76673.o00.1"),
                row(2, 10 * S + 1, "76673.t03.9"),
                row(3, 11 * S + 5, "76673.t03.10"),
                row(4, 12 * S + 7, "76673.o00.1"),
                row(5, 12 * S + 8, "76673.t03.11"));
        for (Row r : rows) {
            tape = tape.add(List.of(r), r.atNanos(), Bench.RETAIN);
        }
        assertEquals(3, tape.seconds().size());
        assertEquals(rows.subList(0, 2), tape.seconds().getFirst().rows(), "a second's rows, added a batch at a time");
        List<Row> read = tape.rowsOf("76673.o00.1");
        assertEquals(List.of(rows.get(0), rows.get(1), rows.get(3), rows.get(4)), read,
                "the first and last seconds, which hold its rows, and not the one between");
        assertEquals(List.of(), tape.rowsOf("76673.c11.2"));
    }

    @Test
    void aDrawsSecondIsReadForTheCausesItShowedThoughItRunsInItsPasss() {
        Row click = row(1, 10 * S, "76673.i01.4");
        Row draw = new Row(2, 11 * S + 3, 0L, 9L, "main", "draw", Model.DRAW_RAN, "#12 76673.i01.4 +1", "76673.t03.40");
        Tape tape = Tape.EMPTY.add(List.of(click), click.atNanos(), Bench.RETAIN)
                .add(List.of(draw), draw.atNanos(), Bench.RETAIN);
        assertEquals(List.of(click, draw), tape.rowsOf("76673.i01.4"),
                "the click's second, and the next one, where the draw that showed it is");
    }

    @Test
    void theRowsAroundAMomentAreInOrderOfTimeThoughALateOneArrivedInTheNextSecond() {
        Row a = row(1, 10 * S, "");
        Row b = row(2, 10 * S + 5, "");
        Row c = row(3, 11 * S + 1, "");
        Row late = row(9, 10 * S + 7, "");   // another thread's, arriving after the second had turned
        Row d = row(4, 11 * S + 2, "");
        Tape tape = Tape.EMPTY.add(List.of(a, b), b.atNanos(), Bench.RETAIN)
                .add(List.of(c, late, d), d.atNanos(), Bench.RETAIN);
        assertEquals(2, tape.seconds().size());
        Messages.RowsAround around = tape.around("run", 10 * S + 7, 5);
        assertEquals(List.of(a, b, late, c, d), around.rows(), "in order of time, the late one where it was stamped");
        assertEquals(2, around.selected(), "the newest at or before the moment");
        assertEquals(List.of(b, late, c, d), tape.newest("run", 4).rows(), "the newest, from where they arrived");
        assertEquals(-1, Tape.EMPTY.around("run", 0L, 4).selected(), "none");
    }
}
