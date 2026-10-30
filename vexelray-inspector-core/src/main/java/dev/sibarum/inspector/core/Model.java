package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * What the panels show at one moment of a run (vexelray-inspector's {@code docs/design.md} section 8): the newest
 * moment its rows reach, and its passes over the {@link Bench#REACH} before it. Replaced, never changed.
 *
 * <p>{@link #fold} is the one way a model moves on, and it is pure: the bench folds each batch on live with it, and
 * {@link Tape#asOf} folds a second's rows onto that second's checkpoint with it. Because it is the same function both
 * ways, the model as of a moment in the past is exactly the model the bench held live at that moment. A new lane or
 * readout is a field here and a few lines in {@link #fold}, and rewinds with no more work.
 *
 * @param newestNanos the newest moment the rows folded so far reach, on the machine's clock
 * @param passes      the run's passes over the reach before that moment, in the order their rows arrived
 */
public record Model(long newestNanos, List<Span> passes) {

    /** Before any row. */
    static final Model EMPTY = new Model(0L, List.of());

    public Model {
        passes = List.copyOf(passes);
    }

    /**
     * This model with {@code rows} folded on, in their order, leaving out any stamped after {@code upToNanos}; passes
     * that ended before the reach are let go. A pass of a run's main loop is its {@code frame} zone, one row at its
     * end whose detail is how long it took.
     */
    Model fold(List<Row> rows, long upToNanos) {
        return fold(rows, upToNanos, Bench.REACH);
    }

    /** {@link #fold(List, long)} keeping the passes over {@code reachNanos} instead of the panels' reach. */
    Model fold(List<Row> rows, long upToNanos, long reachNanos) {
        long newest = newestNanos;
        List<Span> next = new ArrayList<>(passes);
        for (Row r : rows) {
            if (r.atNanos() > upToNanos) {
                continue;
            }
            newest = Math.max(newest, r.atNanos());
            if (r.lane().equals("frame") && r.kind().equals("frame")) {
                long took = Durations.nanos(r.detail());
                if (took >= 0) {
                    next.add(new Span(r.atNanos() - took, r.atNanos()));
                }
            }
        }
        long from = newest - reachNanos;
        next.removeIf(p -> p.toNanos() < from);
        return new Model(newest, next);
    }
}
