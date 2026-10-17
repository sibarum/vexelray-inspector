package dev.sibarum.inspector.rows;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the correlation-log row format, one line at a time, the same way whether the lines come from a probe file
 * or (later) the automation socket's {@code stream} verb.
 *
 * <p>The column order is the probe file's, {@code seq,t_mono_ns,t_wall,thread,lane,kind,detail}, until a header
 * row says otherwise. A header is any line whose first field is {@code seq}: a probe file starts with one, and the
 * stream sends one before its first row (flight-recorder.md §6) with a {@code frame} column added. The header is
 * read by name, so a column added, dropped or moved by a later writer does not shift the others.
 *
 * <p>Quoting is RFC 4180, as {@code sibarum.probe.CsvView.split} reads it back. {@code detail} is the last column
 * so that a naive split keeps working when it holds commas; when there are more fields than columns and
 * {@code detail} is last, the surplus is joined back into it.
 *
 * <p>Forgiving on purpose, like {@code CsvView}: a torn last line (a target killed mid-write is the normal case)
 * or a line that is not a row is skipped, and counted in {@link #skipped()}, rather than failing a read that is
 * part of investigating something else.
 *
 * <p>Not thread-safe: one per source.
 */
public final class RowFormat {

    /** The probe file's own columns, in its own order. */
    public static final List<String> PROBE_COLUMNS =
            List.of("seq", "t_mono_ns", "t_wall", "thread", "lane", "kind", "detail");

    private int seq;
    private int mono;
    private int wall;
    private int thread;
    private int lane;
    private int kind;
    private int detail;
    private int frame;
    private int width;
    private boolean detailLast;
    private long skipped;

    public RowFormat() {
        columns(PROBE_COLUMNS);
    }

    /**
     * Parse one line. A row is returned; a header is adopted and {@code null} is returned; so is a blank line, and a
     * line that is not a row (which is also counted in {@link #skipped()}).
     */
    public Row parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        List<String> f = split(line);
        if (f.getFirst().trim().equalsIgnoreCase("seq")) {
            columns(f);
            return null;
        }
        if (f.size() < width) {
            skipped++;
            return null;
        }
        try {
            String d = f.get(detail);
            if (detailLast && f.size() > width) {
                d = String.join(",", f.subList(detail, f.size()));
            }
            long fr = frame < 0 || f.get(frame).isBlank() ? Row.NO_FRAME : Long.parseLong(f.get(frame).trim());
            return new Row(Long.parseLong(f.get(seq).trim()), Long.parseLong(f.get(mono).trim()), f.get(wall),
                    f.get(thread), f.get(lane), f.get(kind), d, fr);
        } catch (NumberFormatException e) {
            skipped++;
            return null;
        }
    }

    /** Lines that looked like rows and were not: torn, short, or with a number that did not parse. */
    public long skipped() {
        return skipped;
    }

    private void columns(List<String> names) {
        List<String> n = names.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        seq = n.indexOf("seq");
        mono = n.indexOf("t_mono_ns");
        wall = n.indexOf("t_wall");
        thread = n.indexOf("thread");
        lane = n.indexOf("lane");
        kind = n.indexOf("kind");
        detail = n.indexOf("detail");
        frame = n.indexOf("frame");
        if (seq < 0 || mono < 0 || wall < 0 || thread < 0 || lane < 0 || kind < 0 || detail < 0) {
            throw new IllegalArgumentException("a header without the probe columns " + PROBE_COLUMNS + ": " + names);
        }
        width = n.size();
        detailLast = detail == n.size() - 1;
    }

    /** One line into fields, honouring RFC 4180 quoting: the writer's own rules, read back. */
    static List<String> split(String row) {
        List<String> out = new ArrayList<>(8);
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }
}
