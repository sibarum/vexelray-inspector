package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Account;
import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Messages.Details;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.LedgerAt;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.RowsAround;
import dev.sibarum.inspector.core.Messages.Traced;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The frame the cursor is in, as one CSV file (Export frame): a section for each panel as of the cursor, and every
 * row of the cursor's frame, the main loop's pass the rows number. Each section is its title alone on a line, then a
 * header line and its lines, then a blank line, so a spreadsheet opens it as stacked tables. Pure, so the bench's step
 * may build it and a test reads it.
 *
 * <p>Times are numbers in one unit a column, named in its header: lengths in milliseconds, a moment as milliseconds
 * from the cursor and as seconds into the run, and the run's wall clock as UTC where its rows said it. A figure the
 * rows could not fill is blank, never 0.
 */
public final class FrameCsv {

    /** The header of a section of rows: the rows around the cursor, and the frame's. */
    static final List<String> ROW_HEADERS = List.of("Seq", "From cursor (ms)", "Into the run (s)",
            "Wall clock (UTC)", "Frame", "Thread", "Lane", "Kind", "Detail", "Cause");

    private FrameCsv() {
    }

    /**
     * Everything the panels show as of the cursor, and the frame's rows.
     *
     * @param run       the run, as its window's title names it, or its name
     * @param first     the moment of the run's first row read, which "into the run" counts from
     * @param frame     the cursor's frame, the pass its rows number; 0 when the rows number none
     * @param frameRows every row numbered {@code frame}, in order of time then sequence
     */
    public static String of(String run, long first, long frame, Details details, Readouts readouts, Traced traced,
                            LedgerAt ledger, Flows flows, RowsAround around, List<Row> frameRows) {
        StringBuilder out = new StringBuilder();
        long at = details.atNanos();

        section(out, "Frame", List.of("Field", "Value"), List.of(
                List.of("Run", run),
                List.of("Cursor into the run (s)", seconds(details.intoNanos())),
                List.of("Cursor wall clock (UTC)", utc(details.clockNanos())),
                List.of("Frame", frame > 0 ? Long.toString(frame) : ""),
                List.of("Rows of the frame", Long.toString(frameRows.size())),
                List.of("Stretch selected (ms)", flows.selected().map(r -> ms(r.nanos())).orElse(""))));

        List<List<String>> on = new ArrayList<>();
        details.on().ifPresent(e -> event(on, e, at));
        details.row().ifPresent(r -> on.add(List.of("Row", "#" + r.seq() + " " + r.kind())));
        if (on.isEmpty()) {
            on.add(List.of("Event", "none at the cursor"));
        }
        section(out, "Details", List.of("Field", "Value"), on);

        section(out, "Readouts", List.of("Field", "Value"), List.of(
                List.of("Over", readouts.selected() ? "the stretch selected" : "the second before the cursor"),
                List.of("Length (ms)", ms(readouts.nanos())),
                List.of("Draws", Integer.toString(readouts.draws())),
                List.of("Held for the ceiling", Integer.toString(readouts.heldForCeiling())),
                List.of("Held for the GPU", Integer.toString(readouts.heldForGpu())),
                List.of("Recording p50 (ms)", ms(readouts.recordingP50Nanos())),
                List.of("Recording p95 (ms)", ms(readouts.recordingP95Nanos())),
                List.of("Recording max (ms)", ms(readouts.recordingMaxNanos())),
                List.of("Ticks", Integer.toString(readouts.ticks())),
                List.of("Tick p50 (ms)", ms(readouts.tickP50Nanos())),
                List.of("Tick p95 (ms)", ms(readouts.tickP95Nanos())),
                List.of("Tick max (ms)", ms(readouts.tickMaxNanos())),
                List.of("Rows lost", Long.toString(readouts.rowsLost()))));

        List<List<String>> path = new ArrayList<>();
        path.add(List.of("Cause", "", traced.cause()));
        for (Long pass : traced.passes()) {
            path.add(List.of("Pass", "", "#" + pass));
        }
        for (String tick : traced.ticks()) {
            path.add(List.of("Tick", "", tick));
        }
        for (String draw : traced.draws()) {
            path.add(List.of("Draw", "", draw));
        }
        for (Crossed c : traced.crossed()) {
            path.add(List.of(c.to().isEmpty() ? "Write" : "Delivery", c.topic(), c.to()));
        }
        section(out, "Path", List.of("Hop", "Topic", "What"), traced.cause().isEmpty() && path.size() == 1
                ? List.of() : path);

        List<List<String>> threads = new ArrayList<>();
        for (Account a : ledger.accounts()) {
            threads.add(List.of(a.clock(), a.turn(), a.turn().isEmpty() ? "" : ms(a.toNanos() - a.fromNanos()),
                    ms(a.working()), ms(a.waiting()), ms(a.resting()), ms(a.unaccounted()), ms(a.recording()),
                    a.wakes() < 0 ? "" : Integer.toString(a.wakes()), ms(a.latency()), a.note()));
        }
        section(out, "Threads", List.of("Clock", "Turn", "Length (ms)", "Working (ms)", "Waiting (ms)",
                "Resting (ms)", "Unaccounted (ms)", "Recording (ms)", "Wakes", "Wake to run (ms)", "Note"), threads);

        List<List<String>> topics = new ArrayList<>();
        for (Flow f : flows.flows()) {
            topics.add(List.of(f.topic(), f.kind(), ends(f.from()), ends(f.to()), Long.toString(f.written()),
                    Long.toString(f.delivered()), String.format(Locale.ROOT, "%.1f", f.perSecond())));
        }
        section(out, "Flows", List.of("Topic", "Kind", "From", "To", "Written", "Delivered", "Per second"),
                topics);

        section(out, "Rows around the cursor", ROW_HEADERS, rows(around.rows(), at, first));
        section(out, frame > 0 ? "Rows of frame " + frame : "Rows of the frame: the run numbers no frame here",
                ROW_HEADERS, rows(frameRows, at, first));
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<String> headers, List<List<String>> lines) {
        Csv.line(out, List.of(title));
        Csv.line(out, headers);
        for (List<String> line : lines) {
            Csv.line(out, line);
        }
        out.append(Csv.EOL);
    }

    private static void event(List<List<String>> lines, Event e, long at) {
        Mark m = e.mark();
        lines.add(List.of("Lane", e.lane()));
        lines.add(List.of("Kind", m.kind()));
        lines.add(List.of("Thread", m.thread()));
        lines.add(List.of("From, from cursor (ms)", relative(m.fromNanos(), at)));
        lines.add(List.of("To, from cursor (ms)", relative(m.toNanos(), at)));
        lines.add(List.of("Length (ms)", ms(m.toNanos() - m.fromNanos())));
        lines.add(List.of("Frame", m.frame() > 0 ? Long.toString(m.frame()) : ""));
        lines.add(List.of("Cause", m.cause()));
        for (Field f : m.fields()) {
            lines.add(List.of(f.name(), f.value()));
        }
    }

    private static List<List<String>> rows(List<Row> rows, long at, long first) {
        List<List<String>> lines = new ArrayList<>(rows.size());
        for (Row r : rows) {
            lines.add(List.of(Long.toString(r.seq()), relative(r.atNanos(), at), seconds(r.atNanos() - first),
                    utc(r.wallNanos()), r.frame() > 0 ? Long.toString(r.frame()) : "", r.thread(), r.lane(), r.kind(),
                    r.detail(), r.cause()));
        }
        return lines;
    }

    /** Each end as its part, how, and its count, {@code ; } between them. */
    private static String ends(List<End> ends) {
        StringBuilder s = new StringBuilder();
        for (End e : ends) {
            if (!s.isEmpty()) {
                s.append("; ");
            }
            s.append(e.part()).append(' ').append(e.how());
            if (e.count() != End.UNCOUNTED) {
                s.append(' ').append(e.count());
            }
        }
        return s.toString();
    }

    /** A length in milliseconds; blank for one the rows could not fill (negative). */
    static String ms(long nanos) {
        return nanos < 0 ? "" : String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String relative(long nanos, long at) {
        return String.format(Locale.ROOT, "%.3f", (nanos - at) / 1e6);
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.6f", nanos / 1e9);
    }

    /** A wall clock in nanoseconds since the epoch as UTC; blank when the run did not say (0 or less). */
    private static String utc(long wallNanos) {
        return wallNanos <= 0 ? "" : Instant.ofEpochSecond(0L, wallNanos).toString();
    }
}
