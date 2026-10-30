package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * The retained rows of a run, a second at a time, each second beside a checkpoint of the {@link Model} as it stood
 * when that second began (vexelray-inspector's {@code docs/design.md} section 8). Replaced, never changed.
 *
 * <p><b>The past, recomputed.</b> The model as of a moment is its second's checkpoint with that second's rows up to
 * the moment folded on ({@link #asOf}), by the same {@link Model#fold} the bench folds live with: at most a second's
 * rows of work, and exactly what the bench showed then, by construction rather than by keeping pictures.
 *
 * <p><b>What a batch costs.</b> {@link #add} copies the list of seconds and the newest second's rows, not the rows
 * of the whole tape, so a batch's cost is bounded by a second's rows. A second holds the rows that arrived while it
 * was the newest, in their order; a row stamped earlier than its second began (another thread's, arriving late) is
 * kept there, where it was folded live.
 *
 * @param seconds oldest first
 */
public record Tape(List<Second> seconds) {

    /** How much time a checkpoint covers. */
    static final long SECOND = 1_000_000_000L;

    /** Nothing kept yet. */
    static final Tape EMPTY = new Tape(List.of());

    public Tape {
        seconds = List.copyOf(seconds);
    }

    /**
     * One second of the tape.
     *
     * @param fromNanos when it begins, on the machine's clock
     * @param start     the model as it stood then
     * @param rows      the rows that arrived while it was the newest second, in their order
     */
    public record Second(long fromNanos, Model start, List<Row> rows) {
        public Second {
            rows = List.copyOf(rows);
        }
    }

    boolean isEmpty() {
        return seconds.isEmpty();
    }

    /** When the oldest kept second begins: as far back as the cursor can go. */
    long oldestNanos() {
        return seconds.isEmpty() ? 0L : seconds.getFirst().fromNanos();
    }

    /**
     * This tape with {@code batch} kept: a new second begun, with its checkpoint, for each row that crosses into one,
     * and the seconds wholly before {@code retainNanos} of {@code newestNanos} let go.
     */
    Tape add(List<Row> batch, long newestNanos, long retainNanos) {
        if (batch.isEmpty()) {
            return this;
        }
        List<Second> out = new ArrayList<>(seconds);
        long from;
        Model start;
        List<Row> rows;
        if (out.isEmpty()) {
            from = batch.getFirst().atNanos();
            start = Model.EMPTY;
            rows = new ArrayList<>();
        } else {
            Second last = out.removeLast();
            from = last.fromNanos();
            start = last.start();
            rows = new ArrayList<>(last.rows());
        }
        for (Row r : batch) {
            if (r.atNanos() >= from + SECOND) {
                out.add(new Second(from, start, rows));
                start = start.fold(rows, Long.MAX_VALUE);
                from += SECOND * ((r.atNanos() - from) / SECOND);
                rows = new ArrayList<>();
            }
            rows.add(r);
        }
        out.add(new Second(from, start, rows));
        long cut = newestNanos - retainNanos;
        while (out.size() > 1 && out.get(1).fromNanos() <= cut) {
            out.removeFirst();
        }
        return new Tape(out);
    }

    /** The model as of {@code atNanos}: its second's checkpoint, with that second's rows up to it folded on. */
    Model asOf(long atNanos) {
        Second at = null;
        for (Second s : seconds) {
            if (s.fromNanos() > atNanos) {
                break;
            }
            at = s;
        }
        return at == null ? Model.EMPTY : at.start().fold(at.rows(), atNanos);
    }
}
