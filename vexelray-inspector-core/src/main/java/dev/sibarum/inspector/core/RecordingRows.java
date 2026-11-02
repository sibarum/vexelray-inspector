package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Detach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Replay;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.vexelray.framework.api.Source;
import sibarum.atchung.Sender;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A recording's rows, played back at its recorded pace for as long as it lasts: a source, one run per {@link Replay},
 * on a thread of its own. A recording is atchung's probe CSV ({@code docs/probe.md}, "Reading a run back"), the file a
 * run started with {@code -Dprobe.format=csv} writes.
 *
 * <p><b>It is sent as a run is.</b> Its rows cross as {@link Rows}, the batches {@link LinkRows} sends, so the bench
 * folds, keeps and rewinds them exactly as it does a running application's. Each row's time is moved onto the machine's
 * clock as of when the playback started, its first row now, so the lanes follow it as they follow the present.
 *
 * <p><b>At its recorded pace.</b> A row is sent when its moment comes, gathered into a batch every {@link #BATCH_NANOS}
 * at most, as the link batches. The run waits between rows in {@code Thread.sleep}, so stopping it (its thread
 * interrupted) ends the wait.
 *
 * <p>The file's columns are found by its header, as {@code csvview} finds them, so a recording from before causes reads
 * as one without them. It says no pass number (the link's {@code frame}), so its events have none.
 */
@Source(stop = Detach.class)
public final class RecordingRows {

    /** How often a batch is sent at most: the link's own pace. */
    static final long BATCH_NANOS = 10_000_000L;

    private final Sender<Rows> rows;

    public RecordingRows(Sender<Rows> rows) {
        this.rows = rows;
    }

    public Detached run(Replay request) {
        Path file = Path.of(request.path());
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null) {
                return new Detached(request.run(), "the recording is empty");
            }
            Columns columns = Columns.of(split(header));
            if (columns.mono < 0 || columns.kind < 0) {
                return new Detached(request.run(), "it is not a probe recording: its header has no t_mono_ns or kind");
            }
            long origin = Long.MIN_VALUE;
            long lastSent = System.nanoTime();
            List<Row> batch = new ArrayList<>();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                Row r = columns.row(split(line));
                if (r == null) {
                    continue;
                }
                if (origin == Long.MIN_VALUE) {
                    origin = System.nanoTime() - r.atNanos();
                }
                Row placed = new Row(r.seq(), origin + r.atNanos(), r.wallNanos(), 0L, r.thread(), r.lane(), r.kind(),
                        r.detail(), r.cause());
                long now = System.nanoTime();
                if (!batch.isEmpty() && (placed.atNanos() > now || now - lastSent >= BATCH_NANOS)) {
                    rows.send(new Rows(request.run(), 0, 0, batch));
                    batch = new ArrayList<>();
                    lastSent = now;
                }
                long wait = placed.atNanos() - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                }
                batch.add(placed);
            }
            if (!batch.isEmpty()) {
                rows.send(new Rows(request.run(), 0, 0, batch));
            }
            return new Detached(request.run(), "the recording ended");
        } catch (InterruptedException stopped) {
            return new Detached(request.run(), "stopped");
        } catch (IOException | RuntimeException e) {
            return new Detached(request.run(), "it could not be read: " + e.getMessage());
        }
    }

    /** Which column holds what, by the header's names; -1 for a column the file does not have. */
    record Columns(int seq, int mono, int wall, int thread, int lane, int kind, int cause, int detail) {

        static Columns of(List<String> header) {
            return new Columns(header.indexOf("seq"), header.indexOf("t_mono_ns"), header.indexOf("t_wall"),
                    header.indexOf("thread"), header.indexOf("lane"), header.indexOf("kind"), header.indexOf("cause"),
                    header.indexOf("detail"));
        }

        /** The row a line's {@code fields} say, its time still the run's own; null for a line too short to be one. */
        Row row(List<String> fields) {
            if (fields.size() <= Math.max(mono, kind)) {
                return null;
            }
            long at;
            try {
                at = Long.parseLong(fields.get(mono));
            } catch (NumberFormatException e) {
                return null;
            }
            return new Row(number(field(fields, seq)), at, wall(field(fields, wall)), 0L, field(fields, thread),
                    field(fields, lane), field(fields, kind), field(fields, detail), field(fields, cause));
        }

        private static String field(List<String> fields, int at) {
            return at < 0 || at >= fields.size() ? "" : fields.get(at);
        }

        private static long number(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        /** An ISO instant as nanoseconds since the epoch; 0 when there is none, as a row that did not say. */
        private static long wall(String s) {
            if (s.isEmpty()) {
                return 0L;
            }
            try {
                Instant t = Instant.parse(s);
                return t.getEpochSecond() * 1_000_000_000L + t.getNano();
            } catch (DateTimeParseException e) {
                return 0L;
            }
        }
    }

    /**
     * One line of the CSV, its fields: split on commas outside quotes, a quoted field's {@code ""} read as one quote,
     * as atchung writes them. One event is one line, always (probe.md), so no field spans two.
     */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(f.toString());
                f.setLength(0);
            } else {
                f.append(c);
            }
        }
        out.add(f.toString());
        return out;
    }
}
