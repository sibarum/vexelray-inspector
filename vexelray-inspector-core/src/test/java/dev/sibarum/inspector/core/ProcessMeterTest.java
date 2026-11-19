package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Usage;
import dev.sibarum.inspector.core.ProcessMeters.Sample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run's process as the tape keeps it: what a reading's row says, and what a stretch's readings come to. */
class ProcessMeterTest {

    private static final long MS = 1_000_000L;

    private static Row reading(long atNanos, long cpuNanos, long resident) {
        return ProcessMeter.row(new Sample(cpuNanos, resident, 32_000L, 20_000L, 8), atNanos);
    }

    @Test
    void aReadingCrossesAsARowAndComesBack() {
        Sample s = new Sample(123_456_789L, 212L << 20, 32L << 30, 20L << 30, 16);
        Row r = ProcessMeter.row(s, 5 * MS);
        assertTrue(ProcessMeter.isSample(r));
        assertEquals(Optional.of(s), Sample.of(r.detail()));
        assertEquals(Optional.empty(), Sample.of("something else"));
    }

    @Test
    void aStretchsShareRunsFromTheReadingBeforeItsStartToTheLastInsideIt() {
        List<Row> rows = List.of(
                reading(0, 0, 100),
                reading(250 * MS, 100 * MS, 110),       // the last at or before the start, at 300 ms
                new Row(-1, 400 * MS, "main", "frame", "frame", "1ms"),
                reading(500 * MS, 300 * MS, 120),
                reading(1_250 * MS, 850 * MS, 130),     // the last at or before the end, at 1.3 s
                reading(1_500 * MS, 2_000 * MS, 999));  // after it: not counted
        Usage u = ProcessMeter.usage(rows, 300 * MS, 1_300 * MS);
        assertEquals(0.75, u.cpuCores(), 1e-9, "750 ms of CPU over the 1 s between the two readings");
        assertEquals(8, u.machineCores());
        assertEquals(130, u.residentBytes(), "as the last reading had it");
        assertEquals(12_000L, u.memoryUsedBytes());
        assertEquals(32_000L, u.memoryTotalBytes());
    }

    @Test
    void withNoReadingBeforeItTheFirstInsideItStartsTheShare_andOneReadingHasNone() {
        List<Row> rows = List.of(reading(400 * MS, 100 * MS, 1), reading(900 * MS, 350 * MS, 2));
        assertEquals(0.5, ProcessMeter.usage(rows, 300 * MS, 1_300 * MS).cpuCores(), 1e-9);
        Usage one = ProcessMeter.usage(List.of(reading(400 * MS, 100 * MS, 1)), 300 * MS, 1_300 * MS);
        assertEquals(-1.0, one.cpuCores(), "one reading is no stretch of time");
        assertEquals(1, one.residentBytes(), "but it still says the memory");
        assertEquals(Usage.NONE, ProcessMeter.usage(List.of(), 0, 1_000 * MS), "a recording has none");
    }

    @Test
    void aRunThatSaidNoPidIsNotRead() {
        try (ProcessMeter none = ProcessMeter.open(0)) {
            assertEquals(Optional.empty(), none.read());
            assertEquals("the run did not say its pid", none.why());
        }
    }
}
