package dev.sibarum.inspector.clock;

/**
 * The clock the movement is shown on: the target's own time, played a little late and stretched where it is too
 * dense to follow, so every message can be seen and the view still never falls far behind.
 *
 * <p>Two times, both in the target's monotonic nanoseconds:
 * <ul>
 *   <li><b>real</b>, the target's now, as far as the rows say;</li>
 *   <li><b>show</b>, the moment being shown. It never passes real. {@code real - show} is the <b>lag</b>.</li>
 * </ul>
 *
 * <p>Each frame show moves on by the frame's wall time times a <b>speed</b>, chosen so:
 * <ul>
 *   <li><b>Quiet is skipped.</b> Time with no cue in it costs nothing to show, so show jumps straight to the next
 *       cue, or to real when there is none. A run that is not bursting is shown at real time.</li>
 *   <li><b>A burst is stretched.</b> When the cues due in the next frame are more than {@link #cap} a second can
 *       show, the speed drops until they are not, but never below {@code 1 / maxStretch}: 1,000 messages in 20 ms
 *       take most of 200 ms to show at a stretch of 10. Past three quarters of the slack the slowest allowed
 *       speed rises smoothly to real speed at the slack, so a storm that lives at the slack plays evenly at real
 *       speed rather than flipping between a full stretch and a sprint.</li>
 *   <li><b>Lag past the slack is debt, and debt is paid at once.</b> Past {@link #slack} the speed rises with the
 *       debt, so show catches up over about {@link #catchup}, and many cues come due in one frame: they play
 *       together. Lag never exceeds twice the slack; show is pulled up to that if it would.</li>
 * </ul>
 *
 * <p>Show is a function of the frames' wall times and the cues, never of how many frames there were: a frame drawn
 * late just shows a later moment, and nothing backs up behind a slow renderer.
 *
 * <p>Single-threaded: the timeline's.
 */
public final class ShowClock {

    /** The cues not yet shown, in time order. Read, never consumed: what has been shown is released elsewhere. */
    public interface Cues {

        /** The time of the first cue at or after {@code from}, or {@link Long#MAX_VALUE} if there is none. */
        long next(long from);

        /** How many cues fall in {@code (from, to]}, counting no further than {@code limit}. */
        int within(long from, long to, int limit);
    }

    /** No cues at all. */
    public static final Cues NONE = new Cues() {
        @Override
        public long next(long from) {
            return Long.MAX_VALUE;
        }

        @Override
        public int within(long from, long to, int limit) {
            return 0;
        }
    };

    private static final long UNSET = Long.MIN_VALUE;

    private final double cap;
    private final double maxStretch;
    private final long slack;
    private final long catchup;

    private long show = UNSET;
    private double speed = 1;

    /**
     * @param cap        cues a second that can be shown as cues, the fastest animation there is
     * @param maxStretch how much a burst may be slowed down at most: 10 shows 20 ms over 200 ms
     * @param slackNs    how far show may trail real before the lag is debt
     * @param catchupNs  roughly how long a debt takes to pay off
     */
    public ShowClock(double cap, double maxStretch, long slackNs, long catchupNs) {
        if (!(cap > 0) || !(maxStretch >= 1) || slackNs <= 0 || catchupNs <= 0) {
            throw new IllegalArgumentException("cap " + cap + ", stretch " + maxStretch + ", slack " + slackNs
                    + ", catch-up " + catchupNs);
        }
        this.cap = cap;
        this.maxStretch = maxStretch;
        this.slack = slackNs;
        this.catchup = catchupNs;
    }

    /** The Inspector's: 120 cues a second, stretched up to 10 times, 200 ms of slack, debts paid over 100 ms. */
    public static ShowClock standard() {
        return new ShowClock(120, 10, 200_000_000L, 100_000_000L);
    }

    /** Forget everything: the next {@link #advance} starts at real. */
    public void reset() {
        show = UNSET;
        speed = 1;
    }

    /** The moment being shown; {@link Long#MIN_VALUE} before the first advance. */
    public long show() {
        return show;
    }

    /** How fast show moved in the last frame, against real time: 0.1 is a stretch of 10, 3 is catching up. */
    public double speed() {
        return speed;
    }

    /** How far show trails {@code real}; 0 before the first advance. */
    public long lag(long real) {
        return show == UNSET ? 0 : Math.max(0, real - show);
    }

    /** The slack: how far behind is still not debt. */
    public long slack() {
        return slack;
    }

    /**
     * One frame: {@code wallNs} of wall time passed, the target is at {@code real}, and {@code cues} have not been
     * shown yet. Returns the new show.
     */
    public long advance(long wallNs, long real, Cues cues) {
        long dt = Math.max(0, wallNs);
        if (show == UNSET) {
            show = Math.min(real, cues.next(Long.MIN_VALUE));
        }
        // Quiet is skipped: nothing between here and the next cue needs showing.
        long next = cues.next(show);
        if (next > show) {
            show = Math.min(next, real);
        }
        // A burst is stretched: as many cues as the cap allows this frame, and no fewer than a full stretch shows.
        double allowed = Math.max(1, cap * dt / 1e9);
        int due = cues.within(show - 1, show + dt, (int) Math.min(Integer.MAX_VALUE, (long) (allowed * maxStretch) + 1));
        // The slowest it may go: the full stretch while the lag is under three quarters of the slack, rising to real
        // speed at the slack, where debt takes over. Continuous, so a storm settles at real speed with the lag near
        // the slack, instead of flipping between a full stretch and a sprint every frame and showing in clumps.
        long lag = real - show;
        double knee = 0.75 * slack;
        double floor = 1 / maxStretch
                + (1 - 1 / maxStretch) * Math.max(0, Math.min(1, (lag - knee) / (slack - knee)));
        double v = due <= allowed ? 1 : Math.max(floor, allowed / due);
        // Debt is paid at once.
        if (lag > slack) {
            v = Math.max(v, 1 + (double) (lag - slack) / catchup);
        }
        speed = v;
        show = Math.min(real, show + (long) (v * dt));
        if (real - show > 2 * slack) {
            show = real - 2 * slack;
        }
        return show;
    }
}
