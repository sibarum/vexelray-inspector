package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.RowsAround;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The retained rows of a run, a second at a time, each second beside a checkpoint of the {@link Model} as it stood
 * when that second began (vexelray-inspector's {@code docs/design.md} section 8). Replaced, never changed.
 *
 * <p><b>The past, recomputed.</b> The model as of a moment is its second's checkpoint with that second's rows up to
 * the moment folded on ({@link #asOf}), by the same {@link Model#fold} the bench folds live with: at most a second's
 * rows of work, and exactly what the bench showed then, by construction rather than by keeping pictures.
 *
 * <p><b>What a batch costs.</b> {@link #add} copies the list of seconds and adds the batch to the newest second's
 * rows without copying them ({@link Appended}), so a batch costs about its own rows. A second holds the rows that
 * arrived while it was the newest, in their order; a row stamped earlier than its second began (another thread's, arriving late) is
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
     * @param causes    the causes its rows carry, as a filter that can say one is there when it is not, never the other
     *                  way: so that a path reads the seconds its cause is in, not the whole tape
     */
    public record Second(long fromNanos, Model start, List<Row> rows, Causes causes) {
        public Second {
            rows = Appended.of(rows);
        }
    }

    /**
     * The causes some rows carry, two bits of {@link #BITS} for each: about 300 causes a second leave a cause not there
     * said to be there about one time in fifty. Replaced, never changed.
     */
    public static final class Causes {

        static final int BITS = 4096;
        static final Causes NONE = new Causes(new long[BITS / 64]);

        private final long[] bits;

        private Causes(long[] bits) {
            this.bits = bits;
        }

        /** These causes and those of {@code rows}. */
        Causes plus(List<Row> rows) {
            long[] more = bits.clone();
            for (Row r : rows) {
                if (!r.cause().isEmpty()) {
                    add(more, r.cause());
                }
                // A draw runs in its pass's cause and names the causes it showed: it is a hop of theirs too.
                if (Model.isDraw(r)) {
                    for (String shown : Model.shownBy(r)) {
                        add(more, shown);
                    }
                }
            }
            return new Causes(more);
        }

        private static void add(long[] bits, String cause) {
            int h = cause.hashCode();
            set(bits, h);
            set(bits, h >>> 16 ^ h * 31);
        }

        /** Whether {@code cause} may be among them: false only when it is not. */
        boolean mayHave(String cause) {
            int h = cause.hashCode();
            return isSet(h) && isSet(h >>> 16 ^ h * 31);
        }

        private static void set(long[] bits, int h) {
            int bit = h & (BITS - 1);
            bits[bit >>> 6] |= 1L << bit;
        }

        private boolean isSet(int h) {
            int bit = h & (BITS - 1);
            return (bits[bit >>> 6] & 1L << bit) != 0;
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
        Appended<Row> rows;
        Causes causes;
        if (out.isEmpty()) {
            from = batch.getFirst().atNanos();
            start = Model.EMPTY;
            rows = Appended.of();
            causes = Causes.NONE;
        } else {
            Second last = out.removeLast();
            from = last.fromNanos();
            start = last.start();
            rows = Appended.of(last.rows());
            causes = last.causes();
        }
        // The batch a run at a time, each run inside one second.
        int run = 0;
        for (int i = 0; i < batch.size(); i++) {
            Row r = batch.get(i);
            if (r.atNanos() >= from + SECOND) {
                List<Row> part = batch.subList(run, i);
                rows = rows.plus(part);
                run = i;
                out.add(new Second(from, start, rows, causes.plus(part)));
                start = start.fold(rows, Long.MAX_VALUE);
                from += SECOND * ((r.atNanos() - from) / SECOND);
                rows = Appended.of();
                causes = Causes.NONE;
            }
        }
        List<Row> part = batch.subList(run, batch.size());
        out.add(new Second(from, start, rows.plus(part), causes.plus(part)));
        long cut = Bench.since(newestNanos, retainNanos);
        while (out.size() > 1 && out.get(1).fromNanos() <= cut) {
            out.removeFirst();
        }
        return new Tape(out);
    }

    /**
     * The rows kept that {@code cause}'s are among, in the order they were folded: those of every second that may
     * hold one, which is what an event's path is read from ({@link Model#pathOf}).
     */
    List<Row> rowsOf(String cause) {
        List<Row> out = new ArrayList<>();
        for (Second s : seconds) {
            if (s.causes().mayHave(cause)) {
                out.addAll(s.rows());
            }
        }
        return out;
    }

    /** The model as of {@code atNanos}: its second's checkpoint, with that second's rows up to it folded on. */
    Model asOf(long atNanos) {
        Second at = secondAt(atNanos);
        return at == null ? Model.EMPTY : at.start().fold(at.rows(), atNanos);
    }

    /** The order the rows panel shows rows in: by time, then by sequence. */
    static final Comparator<Row> ORDER = Comparator.comparingLong(Row::atNanos).thenComparingLong(Row::seq);

    /** How many of the newest rows {@link #newest} looks among: arrival order is time order but for a late thread's. */
    static final int TAIL = 256;

    /**
     * The {@code count} rows around {@code atNanos} in {@link #ORDER}, the newest at or before it selected, with half
     * the rest after it where there are that many, for {@code run}'s rows panel. A row is kept in the second it is
     * stamped in or a later one, where it arrived, so the rows near a moment are read from its second and the ones
     * either side: a row later than that by a second is not found.
     */
    RowsAround around(String run, long atNanos, int count) {
        return around(run, key(atNanos), count);
    }

    /** As {@link #around(String, long, int)}, about {@code pivot}: a row kept, selected, or a {@link #key}. */
    RowsAround around(String run, Row pivot, int count) {
        long atNanos = pivot.atNanos();
        List<Row> before = new ArrayList<>(count + 1);
        List<Row> after = new ArrayList<>(count + 1);
        int at = 0;
        while (at + 1 < seconds.size() && seconds.get(at + 1).fromNanos() <= atNanos) {
            at++;
        }
        for (int i = Math.max(0, at - 1); i <= Math.min(seconds.size() - 1, at + 1); i++) {
            for (Row r : seconds.get(i).rows()) {
                if (ORDER.compare(r, pivot) <= 0) {
                    keep(before, r, count, true);
                } else {
                    keep(after, r, count, false);
                }
            }
        }
        return window(run, before, after, count);
    }

    /**
     * What a moment is among rows in {@link #ORDER}: after every row stamped then, and before every row stamped later.
     */
    static Row key(long atNanos) {
        return new Row(Long.MAX_VALUE, atNanos, "", "", "", "");
    }

    /**
     * The row next after {@code from} in {@link #ORDER}, or the one before it when {@code back}; null when there is none.
     * Read from the second {@code from} is in onward, or back from the one after it, since a late row is kept a second
     * after it was stamped, until a second has one and the next has been read too.
     */
    Row next(Row from, boolean back) {
        int at = 0;
        while (at + 1 < seconds.size() && seconds.get(at + 1).fromNanos() <= from.atNanos()) {
            at++;
        }
        Row best = null;
        int more = -1;
        for (int i = back ? Math.min(at + 1, seconds.size() - 1) : at; i >= 0 && i < seconds.size() && more != 0;
                i += back ? -1 : 1) {
            for (Row r : seconds.get(i).rows()) {
                int c = ORDER.compare(r, from);
                if (back ? c < 0 && (best == null || ORDER.compare(r, best) > 0)
                        : c > 0 && (best == null || ORDER.compare(r, best) < 0)) {
                    best = r;
                }
            }
            more = best == null ? -1 : more < 0 ? 1 : more - 1;
        }
        return best;
    }

    /**
     * The rows stamped within {@code spanNanos} of {@code atNanos}, either side, in the order they were folded: read
     * from the seconds that may hold them, the one after the last too, since a late row is kept where it arrived. Only
     * those {@code which} takes.
     */
    List<Row> near(long atNanos, long spanNanos, Predicate<Row> which) {
        List<Row> out = new ArrayList<>();
        long from = atNanos - spanNanos;
        long to = atNanos + spanNanos;
        for (int i = 0; i < seconds.size(); i++) {
            Second s = seconds.get(i);
            boolean ended = i + 1 < seconds.size() && seconds.get(i + 1).fromNanos() <= from;
            if (ended || s.fromNanos() > to + SECOND) {
                continue;
            }
            for (Row r : s.rows()) {
                if (r.atNanos() >= from && r.atNanos() <= to && which.test(r)) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * The rows stamped at or after {@code fromNanos}, read back from the newest to arrive, in no order: what a ledger
     * following the newest row reads on every batch, rather than whole seconds. A row arriving more than {@link #LATE}
     * after another stamped later is not found. Only those {@code which} takes.
     */
    List<Row> since(long fromNanos, Predicate<Row> which) {
        List<Row> out = new ArrayList<>();
        for (int i = seconds.size() - 1; i >= 0; i--) {
            List<Row> rows = seconds.get(i).rows();
            for (int j = rows.size() - 1; j >= 0; j--) {
                Row r = rows.get(j);
                if (r.atNanos() >= fromNanos) {
                    if (which.test(r)) {
                        out.add(r);
                    }
                } else if (r.atNanos() < fromNanos - LATE) {
                    return out;
                }
            }
        }
        return out;
    }

    /** How far out of time order {@link #since} reads past the moment it is asked for: a late thread's rows. */
    static final long LATE = 100_000_000L;

    /** The {@code count} newest rows, the newest selected, read from the last {@link #TAIL} to arrive. */
    RowsAround newest(String run, int count) {
        List<Row> before = new ArrayList<>(count + 1);
        int left = TAIL;
        for (int i = seconds.size() - 1; i >= 0 && left > 0; i--) {
            List<Row> rows = seconds.get(i).rows();
            for (int j = rows.size() - 1; j >= 0 && left > 0; j--, left--) {
                keep(before, rows.get(j), count, true);
            }
        }
        return window(run, before, List.of(), count);
    }

    /** {@code before}'s newest selected, and as many of {@code after} as fill half of {@code count}, or what is left. */
    private static RowsAround window(String run, List<Row> before, List<Row> after, int count) {
        int late = Math.min(after.size(), count / 2);
        int early = Math.min(before.size(), count - late);
        late = Math.min(after.size(), count - early);
        List<Row> rows = new ArrayList<>(early + late);
        rows.addAll(before.subList(before.size() - early, before.size()));
        rows.addAll(after.subList(0, late));
        return new RowsAround(run, rows, early - 1);
    }

    /**
     * Put {@code r} into {@code kept}, which is in {@link #ORDER} and keeps at most {@code count}: the newest of them,
     * or the oldest.
     */
    private static void keep(List<Row> kept, Row r, int count, boolean newest) {
        if (kept.size() == count && (newest ? ORDER.compare(r, kept.getFirst()) <= 0
                : ORDER.compare(r, kept.getLast()) >= 0)) {
            return;
        }
        int i = Collections.binarySearch(kept, r, ORDER);
        kept.add(i < 0 ? -i - 1 : i, r);
        if (kept.size() > count) {
            if (newest) {
                kept.removeFirst();
            } else {
                kept.removeLast();
            }
        }
    }

    /** The second {@code atNanos} is in, the newest that begins by then; null when the tape begins after it. */
    Second secondAt(long atNanos) {
        Second at = null;
        for (Second s : seconds) {
            if (s.fromNanos() > atNanos) {
                break;
            }
            at = s;
        }
        return at;
    }
}
