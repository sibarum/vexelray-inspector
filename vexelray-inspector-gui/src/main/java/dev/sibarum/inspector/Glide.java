package dev.sibarum.inspector;

import java.util.ArrayList;
import java.util.List;

/**
 * How the timeline moves: every motion is a smooth {@link Path} of time, and every change is a {@link Handover} from
 * the motion in progress to a new path (vexelray-inspector's {@code docs/design.md} 9.8). Pure functions of time, so the
 * view's asked half can evaluate them at each frame's present and allocate nothing.
 *
 * <h2>No corners, in any derivative</h2>
 *
 * <p>A handover mixes by a {@link #step} that rises from 0 to 1 over its window and is flat in every derivative at both
 * ends, so where it starts the motion matches the old path in position, speed, acceleration and every derivative after
 * them, and where it ends it matches the new one, exactly, in finite time. A spring cannot do this: a new target kinks
 * its velocity, and it never arrives without a snap.
 *
 * <p>What it mixes is <b>speed</b>, not position: the motion's speed goes from the old path's rate to the new one's by
 * the step, and the distance that leaves over (the {@link Handover#gap}) is mixed in by the step too. So a lane that
 * was following the present and is stopped slows to rest without ever going backwards, if it is stopped where it
 * would coast to ({@link #rest}), and a lane told to follow again speeds up, catches up with the present and settles
 * onto it.
 *
 * <p>A handover that starts before the last has finished takes the whole motion so far as its old path, and is made
 * to end no earlier than the last one does, so that by its end the old path has settled onto its own last path and the
 * landing is exact.
 *
 * <h2>Set by eye</h2>
 *
 * <p>These are perceptual, so the constants are what looked right when tried, and say why. {@link #SHARPNESS}: the
 * step built on {@code e^(-c/x)} with {@code c = 0.5} peaks at 1.53 times its average speed and leaves only the first
 * and last tenth of the window nearly still (measured; at {@code c = 1}, the textbook form, a fifth of it is a dead
 * start, which reads as a key lagging, and the peak is twice the average). {@link #window}: a move takes a little
 * longer the further it goes, by its logarithm, so a long move is not a blur and a short one is not sluggish.
 */
final class Glide {

    /** The step's {@code c} in {@code e^(-c/x)}: smaller is a shorter still start and end, and a steeper middle. */
    static final double SHARPNESS = 0.5;

    /** The shortest window, for a move of nothing (a pause), and how a longer move lengthens it. */
    static final long WINDOW_NANOS = 160_000_000L;
    static final long WINDOW_PER_E_NANOS = 60_000_000L;
    static final float WINDOW_SCALE_PX = 50f;
    static final long WINDOW_MAX_NANOS = 500_000_000L;

    private Glide() {
    }

    /**
     * A moment of the run as a function of the machine's present {@code t}: {@code value + rate * (t - origin)}. Stopped
     * is rate 0; following the present is rate 1 through the present.
     */
    record Path(long origin, double value, double rate) {

        static Path stopped(double at) {
            return new Path(0L, at, 0.0);
        }

        static Path following(long now) {
            return new Path(now, now, 1.0);
        }

        double at(long t) {
            return value + rate * (t - origin);
        }
    }

    /**
     * From the motion before it to {@code to}, over {@code windowNanos} from {@code startNanos}. {@code gap} is the
     * distance the change of speed alone would leave between the two at the end, mixed in by the step.
     */
    record Handover(long startNanos, long windowNanos, Path to, double gap) {

        long endNanos() {
            return startNanos + windowNanos;
        }
    }

    /**
     * One thing that moves: the path it started on and each handover since, oldest first. The view keeps two, where
     * the lane's right edge is and how much time the lane spans. Replaced, never changed.
     */
    record Motion(Path from, List<Handover> handovers) {

        Motion {
            handovers = List.copyOf(handovers);
        }

        /** Still, or moving steadily, on {@code path}. */
        static Motion of(Path path) {
            return new Motion(path, List.of());
        }

        double at(long t) {
            return Glide.at(from, handovers, t);
        }

        Path last() {
            return Glide.last(from, handovers);
        }

        boolean moving(long t) {
            return Glide.moving(from, handovers, t);
        }

        /** Where it would come to rest if stopped from {@code now} ({@link Glide#rest}). */
        double rest(long now, long windowNanos) {
            return Glide.rest(from, handovers, now, windowNanos);
        }

        /**
         * The same motion with the handovers that have landed by {@code now} folded into its path: once one has landed,
         * it and all before it are its path exactly, so nothing it does changes.
         */
        Motion settled(long now) {
            for (int i = handovers.size() - 1; i >= 0; i--) {
                if (handovers.get(i).endNanos() <= now) {
                    return new Motion(handovers.get(i).to(), handovers.subList(i + 1, handovers.size()));
                }
            }
            return this;
        }

        /** This motion handed over to {@code to}, from {@code start} over {@code windowNanos} ({@link Glide#handover}). */
        Motion then(long start, long windowNanos, Path to) {
            List<Handover> next = new ArrayList<>(handovers);
            next.add(Glide.handover(from, handovers, start, windowNanos, to));
            return new Motion(from, next);
        }

        /** Whether it is going to {@code to} already: the same rate, and, stopped, at the same place. */
        boolean goingTo(Path to) {
            Path end = last();
            return end.rate() == to.rate() && (end.rate() != 0.0 || end.value() == to.value());
        }
    }

    /** Where the motion is at {@code t}: {@code from}, with each handover in turn applied. */
    static double at(Path from, List<Handover> handovers, long t) {
        double x = from.at(t);
        double rate = from.rate();
        for (int i = 0; i < handovers.size(); i++) {
            Handover h = handovers.get(i);
            double u = (double) (t - h.startNanos()) / h.windowNanos();
            x += (h.to().rate() - rate) * h.windowNanos() * area(u) + step(u) * h.gap();
            rate = h.to().rate();
        }
        return x;
    }

    /** The path the motion ends on. */
    static Path last(Path from, List<Handover> handovers) {
        return handovers.isEmpty() ? from : handovers.getLast().to();
    }

    /** Whether anything still moves at {@code t}: a handover not yet landed, or a path that is not stopped. */
    static boolean moving(Path from, List<Handover> handovers, long t) {
        return last(from, handovers).rate() != 0.0 || (!handovers.isEmpty() && t < handovers.getLast().endNanos());
    }

    /**
     * Where a motion stopped from {@code now} comes to rest, if it is stopped there: it slows by the step and covers
     * half its window at the speed it had, so it never goes back. The window is {@code windowNanos}, or longer if a
     * handover in progress ends later.
     */
    static double rest(Path from, List<Handover> handovers, long now, long windowNanos) {
        long end = Math.max(now + windowNanos, handovers.isEmpty() ? now : handovers.getLast().endNanos());
        return at(from, handovers, end) - last(from, handovers).rate() * (end - now) / 2.0;
    }

    /**
     * The handover from the motion so far to {@code to}, starting at {@code start}: made to end no earlier than the
     * last one, and its gap measured at its end, where the motion so far has settled onto its own last path.
     */
    static Handover handover(Path from, List<Handover> handovers, long start, long windowNanos, Path to) {
        long end = Math.max(start + windowNanos, handovers.isEmpty() ? start : handovers.getLast().endNanos());
        long window = Math.max(1L, end - start);
        double rate = last(from, handovers).rate();
        double gap = to.at(end) - at(from, handovers, end) - (to.rate() - rate) * window / 2.0;
        return new Handover(start, window, to, gap);
    }

    /** How long a move of {@code px} on screen takes. */
    static long window(double px) {
        long w = WINDOW_NANOS + (long) (WINDOW_PER_E_NANOS * Math.log1p(Math.abs(px) / WINDOW_SCALE_PX));
        return Math.min(w, WINDOW_MAX_NANOS);
    }

    /**
     * The step: 0 before 0, 1 after 1, and between them smooth in every derivative with all of them 0 at both ends.
     * {@code f(u) / (f(u) + f(1 - u))} with {@code f(x) = e^(-c/x)}, symmetric about one half.
     */
    static double step(double u) {
        if (u <= 0.0) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 1.0;
        }
        double a = Math.exp(-SHARPNESS / u);
        double b = Math.exp(-SHARPNESS / (1.0 - u));
        return a / (a + b);
    }

    /**
     * The step's integral from 0 to {@code u}: how far a speed rising by the step has gone; past 1 it grows by one a
     * unit.
     *
     * <p>It has no closed form, and its slope must be the step's, or the speed has a corner where a move lands. So it
     * is Simpson's rule over {@link #AREA_INTERVALS}, always on the half where the step is still nearly flat: past one
     * half, by the step's symmetry, {@code area(u) = u - 1/2 + area(1 - u)}. Measured: the slope differs from the step
     * by 3e-9 at worst, and the corner at a landing is below a double's noise. Straight on {@code [0, u]} with sixteen
     * intervals, the first thing tried, it was off by 5e-3 near the end: a corner a test caught, and a pause that
     * crept back by 3 ns.
     */
    static double area(double u) {
        if (u <= 0.0) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 0.5 + (u - 1.0);
        }
        return u <= 0.5 ? simpson(u) : u - 0.5 + simpson(1.0 - u);
    }

    /** How many intervals {@link #area} integrates over: two exponentials each, a frame, per handover in flight. */
    static final int AREA_INTERVALS = 64;

    private static double simpson(double u) {
        double h = u / AREA_INTERVALS;
        double sum = step(u);
        for (int i = 1; i < AREA_INTERVALS; i++) {
            sum += (i % 2 == 1 ? 4.0 : 2.0) * step(i * h);
        }
        return sum * h / 3.0;
    }
}
