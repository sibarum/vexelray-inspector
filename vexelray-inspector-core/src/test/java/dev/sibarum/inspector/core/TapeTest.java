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
}
