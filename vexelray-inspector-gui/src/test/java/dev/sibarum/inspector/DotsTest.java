package dev.sibarum.inspector;

import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph's dots: how many a rate shows, how they are spaced, and where the asked half puts them. */
class DotsTest {

    private static final long S = 1_000_000_000L;

    /** What the asked half set, a call at a time: the x and y of each offset, and each opacity. */
    private static final class Seen implements Drawn {
        final List<float[]> offsets = new ArrayList<>();
        final List<Float> opacities = new ArrayList<>();

        @Override
        public Drawn offset(Node node, float dx, float dy) {
            offsets.add(new float[]{dx, dy});
            return this;
        }

        @Override
        public Drawn stretch(Node node, float factor, float originX) {
            return this;
        }

        @Override
        public Drawn scale(Node node, float factor) {
            return this;
        }

        @Override
        public Drawn opacity(Node node, float opacity) {
            opacities.add(opacity);
            return this;
        }

        @Override
        public Drawn show(Feed feed, int slot) {
            return this;
        }
    }

    @Test
    void aFewMessagesASecondAreADotEachAndMoreAreShownOnALogScaleInSteps() {
        assertEquals(0.0, Dots.shown(0.0));
        assertEquals(0.5, Dots.shown(0.1), "a trickle is still seen");
        assertEquals(3.0, Dots.shown(3.1));
        assertEquals(Dots.HONEST, Dots.shown(Dots.HONEST));
        assertEquals(Dots.MOST_SHOWN, Dots.shown(Dots.TOP_RATE));
        assertEquals(Dots.MOST_SHOWN, Dots.shown(50_000.0), "no more past the top");
        assertTrue(Dots.shown(100.0) > Dots.HONEST && Dots.shown(100.0) < Dots.MOST_SHOWN);
        assertEquals(Dots.shown(100.0), Dots.shown(103.0), "a rate wandering a little stays in its step");
    }

    @Test
    void dotsComeAWholeNumberAlongATrackOrOneAPeriodLongerThanItWhenSparse() {
        float[] busy = Dots.spacing(240f, 4.0);
        assertEquals(10f, busy[0], "96 px/s over 4 a second is 24 px apart: ten along 240 px");
        assertEquals(240f, busy[1]);
        float[] sparse = Dots.spacing(100f, 0.5);
        assertEquals(1f, sparse[0]);
        assertEquals(192f, sparse[1], "one dot, back every two seconds though the track is shorter");
        assertEquals(0f, Dots.spacing(100f, 0.0)[0]);
        assertTrue(Dots.spacing(5_000f, Dots.TOP_RATE)[0] <= Dots.MOST);
    }

    @Test
    void theAskedHalfRunsEachDotAlongItsTrackFadingAtTheEndsAndFadesOutTheSpareOnes() {
        Dots.Dot d = new Dots.Dot(null, 10f, 20f, 1f, 0f, 200f, 200f, 0f);
        Dots.Dot spare = new Dots.Dot(null, 0f, 0f, 0f, 0f, 0f, 1f, 0f);
        Dots s = new Dots(List.of(d, spare), -1L, 0L);
        Seen seen = new Seen();
        long t = S;     // 96 px along
        Dots.place(s, t, false, seen);
        assertEquals(10f + 96f - Dots.BOX / 2f, seen.offsets.getFirst()[0], 1e-3);
        assertEquals(20f - Dots.BOX / 2f, seen.offsets.getFirst()[1], 1e-3);
        assertEquals(List.of(1f, 0f), seen.opacities, "in the middle at full, the spare out");

        Seen near = new Seen();
        Dots.place(s, (long) (S * 197.0 / 96.0), false, near);
        assertEquals(0.3f, near.opacities.getFirst(), 1e-3, "three px from where it lands");
        assertTrue(s.moving());
        assertFalse(Dots.NONE.moving());
    }

    @Test
    void pausedTheDotsStopWhereTheyAreAndFollowingAgainGoOnFromThere() {
        Dots.Dot d = new Dots.Dot(null, 0f, 0f, 1f, 0f, 1_000f, 1_000f, 0f);
        Dots running = new Dots(List.of(d), -1L, 0L);
        Dots stopped = running.following(false, 2 * S);
        assertFalse(stopped.moving());
        assertSame(stopped, stopped.following(false, 3 * S), "stopped already");
        Seen whileStopped = new Seen();
        Dots.place(stopped, 9 * S, false, whileStopped);
        Seen atStop = new Seen();
        Dots.place(running, 2 * S, false, atStop);
        assertEquals(atStop.offsets.getFirst()[0], whileStopped.offsets.getFirst()[0], 1e-3, "where it stopped");

        Dots again = stopped.following(true, 5 * S);
        Seen resumed = new Seen();
        Dots.place(again, 5 * S, false, resumed);
        assertEquals(atStop.offsets.getFirst()[0], resumed.offsets.getFirst()[0], 1e-3, "no jump on following");
        assertEquals(3 * S, again.lostNanos());
        assertEquals(again.dots(), again.laidOut(List.of(d)).dots());
    }
}
