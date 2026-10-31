package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Motion;
import dev.sibarum.inspector.Glide.Path;
import dev.sibarum.inspector.View.Strip;
import dev.sibarum.inspector.core.Messages;
import dev.sibarum.inspector.core.Messages.Span;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view: the whole application built headless by the generated wiring (no window, no GPU), told a timeline as the
 * bench would tell it; and its asked half, called as the frame calls it.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components. The view runs in
 * frames, and there is no loop to run them, so the test runs them while it waits ({@code VexelApplication.frame}):
 * this thread is the GUI thread. No window opens, so the bench never hears {@code WindowOpened} and looks for no run
 * until the timer's beat, and the test tells the view what it would have said.
 */
class ViewTest {

    private static final long MS = 1_000_000L;

    private Shell shell;
    private InspectorAppWiring wiring;

    @BeforeEach
    void build() {
        wiring = new InspectorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    /** Run frames until {@code test} holds: the view hears only in a frame, and this thread runs them. */
    private void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        VexelApplication.frame(shell);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
            VexelApplication.frame(shell);
        }
    }

    /** A {@link Drawn} that remembers each offset and stretch it is asked to set. */
    private static final class Offsets implements Drawn {
        final List<String> set = new ArrayList<>();
        final List<Float> stretched = new ArrayList<>();
        final List<Float> about = new ArrayList<>();

        @Override
        public Drawn offset(Node node, float dx, float dy) {
            set.add(dx + "," + dy);
            return this;
        }

        @Override
        public Drawn stretch(Node node, float factor, float originX) {
            stretched.add(factor);
            about.add(originX);
            return this;
        }
    }

    /** A lane 1000 px wide over 10 s: 0.1 px a millisecond, and the strip at rest 7000 px left of it. */
    private static final float LANE = 1000f;
    private static final float SCALE = LANE / View.SPAN_NANOS;
    private static final float REST = -View.SLIDE_LANES * LANE;

    private static Motion spanning(double nanos) {
        return Motion.of(Path.stopped(Math.log(nanos)));
    }

    @Test
    void aTimelineToldIsDrawnForTheLaneAndFollowedWhileLive() throws InterruptedException {
        Timeline t = new Timeline("calculator-11", "Inspecting Calculator (calculator-11)", true, 900 * MS,
                List.of(new Span(880 * MS, 890 * MS)), Optional.empty());
        shell.bus().publish(Messages.TIMELINE_TOPIC, t);
        eventually("the lane laid out and the timeline drawn", () -> wiring.view().state.laneW() > 0f
                && wiring.view().state.edge().last().rate() == 1.0);
        Strip s = wiring.view().state;
        assertEquals(900 * MS, s.edgeNanos(), "the newest row's moment is where the picture puts the right edge");
        assertEquals(List.of(), s.edge().handovers(), "the first timeline is placed, not glided to");
        assertSame(wiring.ui().strip(), s.strip(), "the handle is the strip in the lane");
        float lane = wiring.ui().lane().layout().rect().w();
        assertEquals(lane, s.laneW());
        assertEquals(lane / View.SPAN_NANOS, s.pictureScale(), 1e-12f, "built at the first zoom");
        assertEquals(lane * (1 + 2 * View.SLIDE_LANES), wiring.ui().strip().layout().rect().w(), 1f,
                "the strip reaches past the lane both sides, and the lane is not widened by it");
    }

    @Test
    void theStripSlidesLeftByHowFarThePresentHasMovedOnSinceItsEdge() {
        Offsets out = new Offsets();
        Strip s = new Strip(null, 1_000 * MS, SCALE, LANE, Motion.of(Path.following(0L)), spanning(View.SPAN_NANOS));
        long due = View.draw(s, new Frame(7L, 1_000 * MS + 250 * MS, false), out);
        assertEquals(REST - 25f, offset(out), 0.01f, "250 ms on, at 0.1 px a millisecond: 25 px");
        assertEquals(1f, out.stretched.getLast(), 1e-5f, "at the zoom it was built at, not stretched");
        assertEquals(7L, due, "following: the next frame, on the tree's clock");
    }

    @Test
    void stoppedAtTheEdgeTheStripIsWhereLayoutPutItAndNothingIsDue() {
        Offsets out = new Offsets();
        Strip s = new Strip(null, 1_000 * MS, SCALE, LANE, Motion.of(Path.stopped(1_000 * MS)),
                spanning(View.SPAN_NANOS));
        long due = View.draw(s, new Frame(7L, 5_000 * MS, false), out);
        assertEquals(REST, offset(out), 0.01f);
        assertEquals(Frame.NEVER, due);
    }

    @Test
    void aGlideIsDueUntilItLandsAndUnderReducedMotionGoesStraightThere() {
        Motion back = Motion.of(Path.stopped(1_000 * MS)).then(0L, 200 * MS, Path.stopped(0.0));
        Strip s = new Strip(null, 1_000 * MS, SCALE, LANE, back, spanning(View.SPAN_NANOS));
        Offsets out = new Offsets();
        assertEquals(7L, View.draw(s, new Frame(7L, 100 * MS, false), out), "on its way: due");
        assertEquals(REST + 50f, offset(out), 0.01f, "halfway in time, halfway there: the step is symmetric");
        assertEquals(Frame.NEVER, View.draw(s, new Frame(7L, 200 * MS, false), out), "landed");
        assertEquals(REST + 100f, offset(out), 0.01f);
        View.draw(s, new Frame(7L, 1L, true), out);
        assertEquals(REST + 100f, offset(out), 0.01f, "reduced motion: where it is going, at once");
    }

    @Test
    void aZoomStretchesThePictureAboutTheRightEdgeAndGlidesEachDoublingAlike() {
        Motion in = spanning(View.SPAN_NANOS).then(0L, 200 * MS, Path.stopped(Math.log(View.SPAN_NANOS / 2.0)));
        Strip s = new Strip(null, 1_000 * MS, SCALE, LANE, Motion.of(Path.stopped(1_000 * MS)), in);
        Offsets out = new Offsets();
        assertEquals(7L, View.draw(s, new Frame(7L, 100 * MS, false), out), "zooming: due");
        assertEquals(Math.sqrt(2), out.stretched.getLast(), 1e-4, "halfway in time, halfway in doublings");
        assertEquals(LANE * (1 + View.SLIDE_LANES), out.about.getLast(), "about the edge moment's place");
        assertEquals(REST, offset(out), 0.01f, "the edge moment stays at the lane's right edge");
        assertEquals(Frame.NEVER, View.draw(s, new Frame(7L, 200 * MS, false), out), "landed");
        assertEquals(2f, out.stretched.getLast(), 1e-5f, "twice as many pixels a nanosecond");
    }

    @Test
    void aPassIsAFillAsLongAsItTookAndAOnePixelLineAtItsEnd() {
        long newest = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, newest, List.of(new Span(newest - 500 * MS, newest - 400 * MS),
                new Span(newest - 100_000L, newest - 99_900L), new Span(newest - 11_000 * MS, newest - 10_500 * MS)),
                Optional.empty());
        Picture p = View.passes(t, LANE, 20f, SCALE, Color.WHITE, Color.BLACK, newest - View.SPAN_NANOS,
                Double.MAX_VALUE);
        assertEquals(List.of("950+10", "1000+0"), fills(p),
                "100 ms is 10 px, a far shorter pass as long as it took, and one before the range not drawn");
        assertEquals(List.of("960 w1", "999 w1"), lines(p), "a pixel's line inside each end, which no zoom stretches");
    }

    @Test
    void thePassTheCursorIsOnIsDrawnOverTheOthersTheLanesHeightWithATwoPixelLine() {
        long at = 20_000 * MS;
        Span on = new Span(at - 100 * MS, at);
        Timeline t = new Timeline("r", "", false, at, List.of(new Span(at - 500 * MS, at - 400 * MS), on),
                Optional.of(on));
        Picture p = View.passes(t, LANE, 20f, SCALE, Color.WHITE, Color.BLACK, at - View.SPAN_NANOS, at);
        assertEquals(List.of("950+10", "990+10", "990+10"), fills(p), "drawn last, so over the others");
        assertEquals("999 w2", lines(p).getLast(), "ending at its end");
        Picture.Fill mark = (Picture.Fill) p.marks().get(p.marks().size() - 2);
        assertEquals(0.0, mark.y());
        assertEquals(20.0, mark.h(), "the lane's whole height");
        assertEquals(Color.BLACK, mark.color());
    }

    @Test
    void aClickGoesToThePassNearestItWithinReach() {
        List<Span> passes = List.of(new Span(100, 200), new Span(300, 310), new Span(1_000, 1_001));
        assertEquals(Optional.of(new Span(100, 200)), View.nearest(passes, 150, 5), "on it");
        assertEquals(Optional.of(new Span(300, 310)), View.nearest(passes, 260, 50), "the nearer of two");
        assertEquals(Optional.of(new Span(1_000, 1_001)), View.nearest(passes, 1_004, 5), "just past a short one");
        assertEquals(Optional.empty(), View.nearest(passes, 600, 50), "none within reach");
    }

    private static float offset(Offsets out) {
        return Float.parseFloat(out.set.getLast().split(",")[0]);
    }

    /** Each fill's left edge, relative to the lane's left at rest, and its width, rounded. */
    private static List<String> fills(Picture p) {
        return p.marks().stream().filter(m -> m instanceof Picture.Fill).map(m -> (Picture.Fill) m)
                .map(f -> Math.round(f.x() + REST) + "+" + Math.round(f.w())).toList();
    }

    /** Each line's x, relative to the lane's left at rest, rounded, and its thickness. */
    private static List<String> lines(Picture p) {
        return p.marks().stream().filter(m -> m instanceof Picture.Line).map(m -> (Picture.Line) m)
                .map(l -> Math.round(l.x0() + REST) + " w" + Math.round(l.thickness())).toList();
    }
}
