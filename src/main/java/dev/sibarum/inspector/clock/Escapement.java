package dev.sibarum.inspector.clock;

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
 * <p>Every other lane is ignored here; it is for the other mechanisms.
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

        /** A run began ({@code origin}), or ended ({@code why}); the view says so. */
        void source(String state);
    }

    private final Listener listener;

    private Beat beat = Beat.NONE;
    private long lastPresent = -1;
    private long spanNanos = -1;
    private long parkedAt = -1;

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
        listener.source(origin);
    }

    @Override
    public void end(String why) {
        listener.source(why);
    }

    @Override
    public void row(Row row) {
        String kind = row.kind();
        if (!row.lane().equals("frame") || kind.startsWith("wake.")) {
            return;                     // wake.post, wake.wired: how a wake was delivered, not that one happened
        }
        if (kind.equals(Row.LOOP_PARK)) {
            parkedAt = row.monoNanos();
            beat = with(true, row.detail().trim(), beat.restedNanos(), beat.wake(), beat.waker(), row);
            listener.rest(beat);
            return;
        }
        if (parkedAt >= 0) {
            boolean woken = kind.equals(Row.WAKE);
            long rested = row.monoNanos() - parkedAt;
            parkedAt = -1;
            String why = woken ? reason(row.detail()) : Durations.nanos(beat.budget()) == 0 ? "no rest: a 0ms park"
                    : "budget spent";
            beat = with(false, beat.budget(), rested, why, woken ? row.thread() : "", row);
            listener.wake(beat);
        }
        if (kind.equals(Row.FRAME_SPAN)) {
            spanNanos = Durations.nanos(row.detail());
        } else if (kind.equals(Row.FRAME_PRESENT)) {
            long interval = lastPresent < 0 ? -1 : row.monoNanos() - lastPresent;
            lastPresent = row.monoNanos();
            beat = new Beat(beat.frames() + 1, row.presentedFrame(), spanNanos, interval, false, beat.budget(),
                    beat.restedNanos(), beat.wake(), beat.waker(), row.monoNanos());
            spanNanos = -1;
            listener.tick(beat);
        }
    }

    private Beat with(boolean resting, String budget, long rested, String wake, String waker, Row row) {
        return new Beat(beat.frames(), beat.frame(), beat.frameNanos(), beat.intervalNanos(), resting, budget,
                rested, wake, waker, row.monoNanos());
    }

    /** {@code mutation on Gui@7823a2f9} is a mutation: which tree is noise at this altitude. */
    static String reason(String detail) {
        int on = detail.indexOf(" on ");
        return (on < 0 ? detail : detail.substring(0, on)).trim();
    }
}
