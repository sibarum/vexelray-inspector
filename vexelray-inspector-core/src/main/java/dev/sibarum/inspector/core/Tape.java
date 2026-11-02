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
