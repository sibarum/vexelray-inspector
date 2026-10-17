package dev.sibarum.inspector.rows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySourceTest {

    @TempDir
    Path dir;

    /** A clock that only moves when waited on, and remembers each wait. */
    static class FakePacer implements Pacer {
        long now = 5_000;               // any origin: the replay must not assume zero
        final List<Long> waits = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void waitUntil(long nanos) {
            waits.add(nanos);
            now = Math.max(now, nanos);
        }
    }

    /** Records each row with the fake clock's time at delivery. */
    static final class Recording implements RowSink {
        final FakePacer clock;
        final List<String> seen = new ArrayList<>();

        Recording(FakePacer clock) {
            this.clock = clock;
        }

        @Override
        public void begin(String origin) {
            seen.add("begin");
        }

        @Override
        public void row(Row row) {
            seen.add(row.seq() + "@" + (clock.now - 5_000));
        }

        @Override
        public void end(String why) {
            seen.add("end");
        }
    }

    private static Row row(long seq, long mono) {
        return new Row(seq, mono, "w", "main", "frame", "frame.present", "#" + seq, Row.NO_FRAME);
    }

    @Test
    void eachRowIsDeliveredAtItsRecordedOffsetFromTheFirst() {
        FakePacer clock = new FakePacer();
        Recording sink = new Recording(clock);
        ReplaySource replay = new ReplaySource(Path.of("x.csv"), 1, false, clock);

        boolean finished = replay.play(List.of(row(1, 1_000_000), row(2, 1_000_000), row(3, 4_000_000),
                row(4, 21_000_000)), sink);

        assertTrue(finished);
        // Offsets are from the first row (recorded at 1ms), not from the target's start.
        assertEquals(List.of("1@0", "2@0", "3@3000000", "4@20000000"), sink.seen);
        // Rows due now are not waited for: two waits, for the two rows in the future.
        assertEquals(2, clock.waits.size());
    }

    @Test
    void speedDividesThePace() {
        FakePacer clock = new FakePacer();
        Recording sink = new Recording(clock);
        new ReplaySource(Path.of("x.csv"), 4, false, clock).play(List.of(row(1, 0), row(2, 8_000_000)), sink);
        assertEquals(List.of("1@0", "2@2000000"), sink.seen);
    }

    @Test
    void aReplayThatFellBehindCatchesUpWithoutSkipping() {
        FakePacer clock = new FakePacer() {
            @Override
            public void waitUntil(long nanos) {
                super.waitUntil(nanos);
                now += 10_000_000;      // every wait oversleeps by 10ms
            }
        };
        Recording sink = new Recording(clock);
        new ReplaySource(Path.of("x.csv"), 1, false, clock)
                .play(List.of(row(1, 0), row(2, 1_000_000), row(3, 2_000_000), row(4, 3_000_000)), sink);
        // Row 2 arrives late, rows 3 and 4 are already due and go at once; none is dropped or reordered.
        assertEquals(List.of("1@0", "2@11000000", "3@11000000", "4@11000000"), sink.seen);
    }

    @Test
    void theFileIsPlayedInTimeOrderOnItsOwnThreadAndEnds() throws Exception {
        Path file = dir.resolve("run.csv");
        Files.writeString(file, """
                seq,t_mono_ns,t_wall,thread,lane,kind,detail
                2,3000000,w,main,frame,loop.park,200ms
                1,1000000,w,main,frame,frame.present,#1
                3,2000000,w,worker,frame,wake,"mutation on Gui@1, twice"
                """);
        List<String> seen = new ArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        try (ReplaySource replay = new ReplaySource(file, 1, false, Pacer.SYSTEM)) {
            replay.start(new RowSink() {
                @Override
                public void begin(String origin) {
                    seen.add(origin);
                }

                @Override
                public void row(Row row) {
                    seen.add(row.seq() + " " + Thread.currentThread().getName());
                }

                @Override
                public void end(String why) {
                    seen.add(why);
                    ended.countDown();
                }
            });
            assertTrue(ended.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("replay run.csv", "1 inspector-replay", "3 inspector-replay", "2 inspector-replay",
                "end of run.csv"), seen);
    }

    @Test
    void anUnreadableFileEndsTheRunAndSaysWhy() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        String[] why = new String[1];
        try (ReplaySource replay = new ReplaySource(dir.resolve("missing.csv"), 1, true, Pacer.SYSTEM)) {
            replay.start(new RowSink() {
                @Override
                public void begin(String origin) {
                }

                @Override
                public void row(Row row) {
                }

                @Override
                public void end(String w) {
                    why[0] = w;
                    ended.countDown();
                }
            });
            assertTrue(ended.await(5, TimeUnit.SECONDS));
        }
        assertTrue(why[0].startsWith("could not read"), why[0]);
    }
}
