package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Handover;
import dev.sibarum.inspector.Glide.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeline's motion, as numbers (design.md 9.8): it lands exactly where it is going, a pause never goes backwards,
 * and nothing jumps in position, speed or acceleration, however often it is interrupted. Times here start near zero,
 * so that differences of a few nanoseconds are not lost to a double's precision at a real clock's magnitude.
 */
class GlideTest {

    private static final long MS = 1_000_000L;

    /** A motion being built: a path, and the handovers added to it, each at its own moment. */
    private Path from = Path.following(0L);
    private final List<Handover> handovers = new ArrayList<>();

    private void hand(long at, long window, Path to) {
        handovers.add(Glide.handover(from, List.copyOf(handovers), at, window, to));
    }

    private double at(long t) {
        return Glide.at(from, handovers, t);
    }

    /**
     * Sampled every {@code dt} from {@code t0} to {@code t1}: the largest change between neighbouring samples of speed and
     * of acceleration, each over the largest magnitude it reaches. A jump in either is a large fraction of it; a
     * continuous one changes by a little each sample.
     */
    private double[] worstChanges(long t0, long t1, long dt) {
        double maxV = 0, maxA = 0, jumpV = 0, jumpA = 0;
        double x0 = at(t0), x1 = at(t0 + dt), x2 = at(t0 + 2 * dt);
        double v0 = (x1 - x0) / dt, v1 = (x2 - x1) / dt;
        double a0 = (v1 - v0) / dt;
        for (long t = t0 + 3 * dt; t <= t1; t += dt) {
            double x3 = at(t);
            double v2 = (x3 - x2) / dt;
            double a1 = (v2 - v1) / dt;
            maxV = Math.max(maxV, Math.abs(v2));
            maxA = Math.max(maxA, Math.abs(a1));
            jumpV = Math.max(jumpV, Math.abs(v2 - v1));
            jumpA = Math.max(jumpA, Math.abs(a1 - a0));
            x2 = x3;
            v1 = v2;
            a0 = a1;
        }
        return new double[]{jumpV / maxV, jumpA / maxA};
    }

    @Test
    void theStepIsFlatAtBothEndsAndSymmetric() {
        assertEquals(0.0, Glide.step(0.0));
        assertEquals(1.0, Glide.step(1.0));
        assertEquals(0.5, Glide.step(0.5), 1e-12);
        assertEquals(1.0, Glide.step(0.3) + Glide.step(0.7), 1e-12);
        assertTrue(Glide.step(0.05) < 1e-4, "still, near the start");
        assertEquals(0.5, Glide.area(1.0), 1e-12, "the area under the step is exactly a half, so a landing is exact");
    }

    @Test
    void aPauseSlowsToRestWhereItSaidWithoutGoingBack() {
        long w = Glide.window(0.0);
        double rest = Glide.rest(from, handovers, 100 * MS, w);
        hand(100 * MS, w, Path.stopped(rest));
        double previous = at(100 * MS);
        for (long t = 100 * MS; t <= 100 * MS + w + 10 * MS; t += 100_000L) {
            double x = at(t);
            assertTrue(x >= previous - 1e-6, "never back: " + (x - previous) + " at " + t);
            previous = x;
        }
        assertEquals(rest, at(100 * MS + w), 1e-6, "lands where it said");
        assertEquals(rest, at(900 * MS), 1e-6, "and stays");
        assertEquals(100 * MS + w / 2.0, rest, 1e-6, "half its window on, at the speed it had");
    }

    @Test
    void followingAgainCatchesUpWithThePresentAndSettlesOntoIt() {
        from = Path.stopped(-3_000 * MS);        // paused three seconds back
        long w = Glide.window(500.0);
        hand(0L, w, Path.following(0L));
        assertEquals(w, at(w), 1e-6, "on the present when it lands");
        assertEquals(w + 50 * MS, at(w + 50 * MS), 1e-6, "and following it after");
        double previous = at(0L);
        for (long t = 0L; t <= w; t += 100_000L) {
            assertTrue(at(t) >= previous - 1e-6, "never back on the way");
            previous = at(t);
        }
    }

    @Test
    void nothingJumpsInPositionSpeedOrAccelerationThroughInterruptions() {
        // Following; a pause; before it lands, a step back; before that lands, two more; then following again.
        long w = Glide.window(0.0);
        hand(100 * MS, w, Path.stopped(Glide.rest(from, handovers, 100 * MS, w)));
        hand(160 * MS, Glide.window(91), Path.stopped(at(160 * MS) - 1_000 * MS));
        hand(200 * MS, Glide.window(91), Path.stopped(at(200 * MS) - 2_000 * MS));
        hand(233 * MS, Glide.window(9), Path.stopped(at(233 * MS) - 2_100 * MS));
        hand(700 * MS, Glide.window(200), Path.following(700 * MS));
        double[] worst = worstChanges(0L, 1_400 * MS, 20_000L);
        assertTrue(worst[0] < 0.01, "speed changes by at most 1% of its largest a sample: " + worst[0]);
        assertTrue(worst[1] < 0.02, "acceleration by at most 2% of its largest a sample: " + worst[1]);
        assertEquals(1_400 * MS, at(1_400 * MS), 1e-3, "and it lands back on the present");
    }

    @Test
    void anInterruptedMoveStillLandsExactlyOnTheLastTarget() {
        from = Path.stopped(0.0);
        hand(0L, 200 * MS, Path.stopped(1_000 * MS));
        hand(50 * MS, 200 * MS, Path.stopped(-500 * MS));
        hand(60 * MS, 100 * MS, Path.stopped(250 * MS));
        long end = handovers.getLast().endNanos();
        assertTrue(end >= 250 * MS, "a handover ends no earlier than the one before it");
        assertEquals(250 * MS, at(end), 1e-6);
        assertEquals(250 * MS, at(end + 1_000 * MS), 1e-6);
    }
}
