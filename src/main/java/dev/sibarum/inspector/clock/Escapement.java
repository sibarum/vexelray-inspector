package dev.sibarum.inspector.clock;

import dev.sibarum.inspector.rows.LinkSource;
import dev.sibarum.inspector.rows.Row;
import dev.sibarum.inspector.rows.RowSink;

/**
 * The escapement and balance wheel, as state: the target's main loop read from its {@code frame} lane.
 *
 * <p>The loop's two heartbeat rows are the whole input (automation.md §4, <i>Reading it</i>):
 * <ul>
 *   <li>{@code frame.present}, every frame, unconditionally: one <b>tick</b>;</li>
 *   <li>{@code loop.park}, whenever the loop parks, with the budget it parked for: the wheel <b>rests</b>.</li>
 * </ul>
 * Around them, the {@code frame} span says how long the frame took, and the first {@code wake} row after a park
 * says what ended the rest. The rest ends at the first frame-lane row after the park that is not a park, which is
 * the wake when there is one and the next frame's first stage when the budget simply ran out. The {@code wake.*}
 * rows ({@code wake.post}, {@code wake.wired}) say how a wake was delivered, and are not one.
 *
 * <p>Each interval between two frames is judged as {@code csvview --gaps} judges it: the part the park inside it does
 * not cover (all of it with no park, none of it under {@code forever}) is a stall when it is over {@link Beat#STALL}.
 * A rest that outlasts its budget by that much is said at its wake, without waiting for the frame.
 *
 * <p>The one other row read is the stream's own {@code stream}/{@code lost}: rows the target wrote and the
 * Inspector never saw. It is counted, and nothing is measured across it. Every other lane is ignored here; it is for
 * the other mechanisms.
 *
 * <p>Called on the source's thread, one row at a time; the listener is called on that thread too, after the
 * state changed, with the whole new {@link Beat}.
 */
public final class Escapement implements RowSink {

    /** Told of every change, with the whole new state. */
    public interface Listener {

        /** A frame was presented: the wheel ticks. */
        void tick(Beat beat);

        /** The loop parked: the wheel rests. */
        void rest(Beat beat);

        /** The rest ended; {@code beat.wake()} says why if a wake row said so. */
        void wake(Beat beat);

        /** The stream lost rows: {@code beat.lostRows()} has grown, and the next frame missed beats before it. */
        void lost(Beat beat);

        /** A run began ({@code origin}), or ended ({@code why}); the view says so. */
        void source(String state);

        /** Tell {@code first} and then {@code second} of everything: the window, and the history beside it. */
        static Listener both(Listener first, Listener second) {
            return new Listener() {
                @Override
                public void tick(Beat beat) {
                    first.tick(beat);
                    second.tick(beat);
                }

                @Override
                public void rest(Beat beat) {
                    first.rest(beat);
                    second.rest(beat);
                }

                @Override
                public void wake(Beat beat) {
                    first.wake(beat);
                    second.wake(beat);
                }

                @Override
                public void lost(Beat beat) {
                    first.lost(beat);
                    second.lost(beat);
                }

                @Override
                public void source(String state) {
                    first.source(state);
                    second.source(state);
                }
            };
        }
    }

    private final Listener listener;

    private Beat beat = Beat.NONE;
    private long lastPresent = -1;
    private long spanNanos = -1;
    private long parkedAt = -1;
    private long coverNanos = -1;       // the budget parked for since the last frame, or -1 if the loop did not park
    private boolean stallSaid;          // this interval's stall was already counted, at a late wake

    public Escapement(Listener listener) {
        this.listener = listener;
    }

    /** The state as of the last row. */
    public Beat beat() {
        return beat;
    }

    @Override
    public void begin(String origin) {
        beat = Beat.NONE;
        lastPresent = -1;
        spanNanos = -1;
        parkedAt = -1;
        coverNanos = -1;
        stallSaid = false;
        listener.source(origin);
    }

    @Override
    public void end(String why) {
        listener.source(why);
    }

    @Override
    public void row(Row row) {
        if (row.is(LinkSource.STREAM, LinkSource.LOST)) {
            lost(row);
            return;
        }
        String kind = row.kind();
        if (!row.lane().equals("frame") || kind.startsWith("wake.")) {
            return;                     // wake.post, wake.wired: how a wake was delivered, not that one happened
        }
        if (kind.equals(Row.LOOP_PARK)) {
            parkedAt = row.monoNanos();
            coverNanos = cover(coverNanos, Math.max(0, Durations.nanos(row.detail())));
            beat = with(true, row.detail().trim(), beat.restedNanos(), beat.wake(), beat.waker(), row,
                    beat.uncoveredNanos(), beat.stalls());
            listener.rest(beat);
            return;
        }
        if (parkedAt >= 0) {
            boolean woken = kind.equals(Row.WAKE);
            long rested = row.monoNanos() - parkedAt;
            parkedAt = -1;
            String why = woken ? reason(row.detail()) : Durations.nanos(beat.budget()) == 0 ? "no rest: a 0ms park"
                    : "budget spent";
            // The rest is the gap judged now: one that ended within its budget clears a stall shown from before it.
            long overrun = coverNanos == Long.MAX_VALUE ? 0 : Math.max(0, rested - coverNanos);
            boolean late = overrun > Beat.STALL;
            beat = with(false, beat.budget(), rested, why, woken ? row.thread() : "", row,
                    overrun, beat.stalls() + (late && !stallSaid ? 1 : 0));
            stallSaid |= late;
            listener.wake(beat);
        }
        if (kind.equals(Row.FRAME_SPAN)) {
            spanNanos = Durations.nanos(row.detail());
        } else if (kind.equals(Row.FRAME_PRESENT)) {
            long interval = lastPresent < 0 ? -1 : row.monoNanos() - lastPresent;
            long uncovered = interval < 0 ? -1 : coverNanos < 0 ? interval : Math.max(0, interval - coverNanos);
            boolean stall = uncovered > Beat.STALL && !stallSaid;
            lastPresent = row.monoNanos();
            beat = new Beat(beat.frames() + 1, row.presentedFrame(), spanNanos, interval, false, beat.budget(),
                    beat.restedNanos(), beat.wake(), beat.waker(), row.monoNanos(), beat.lostRows(), uncovered,
                    beat.stalls() + (stall ? 1 : 0));
            spanNanos = -1;
            coverNanos = -1;
            stallSaid = false;
            listener.tick(beat);
        }
    }

    /** Two parks in one interval cover both their budgets; {@code forever} covers everything. */
    private static long cover(long sofar, long budget) {
        return sofar < 0 ? budget : sofar > Long.MAX_VALUE - budget ? Long.MAX_VALUE : sofar + budget;
    }

    /**
     * Rows the stream lost ({@code a..b}): count them, and forget everything that would otherwise be measured across
     * the gap. The frame before, a span begun and a park under way may each have ended inside it, so the next
     * interval and rest are unknown rather than made up, and an unknown interval is never judged a stall.
     */
    private void lost(Row row) {
        lastPresent = -1;
        spanNanos = -1;
        parkedAt = -1;
        coverNanos = -1;
        stallSaid = false;
        beat = new Beat(beat.frames(), beat.frame(), beat.frameNanos(), beat.intervalNanos(), false, beat.budget(),
                -1, "", "", row.monoNanos(), beat.lostRows() + count(row.detail()), -1, beat.stalls());
        listener.lost(beat);
    }

    /** {@code 2..4} is three rows; text that is not a range counts as one gap of unknown size, so as one row. */
    static long count(String range) {
        int dots = range.indexOf("..");
        try {
            long n = Long.parseLong(range.substring(dots + 2).trim()) - Long.parseLong(range.substring(0, dots).trim())
                    + 1;
            return Math.max(1, n);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private Beat with(boolean resting, String budget, long rested, String wake, String waker, Row row,
                      long uncovered, long stalls) {
        return new Beat(beat.frames(), beat.frame(), beat.frameNanos(), beat.intervalNanos(), resting, budget,
                rested, wake, waker, row.monoNanos(), beat.lostRows(), uncovered, stalls);
    }

    /** {@code mutation on Gui@7823a2f9} is a mutation: which tree is noise at this altitude. */
    static String reason(String detail) {
        int on = detail.indexOf(" on ");
        return (on < 0 ? detail : detail.substring(0, on)).trim();
    }
}
