package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Handover;
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

    /** A {@link Drawn} that remembers each offset it is asked to set. */
    private static final class Offsets implements Drawn {
        final List<String> set = new ArrayList<>();

        @Override
        public Drawn offset(Node node, float dx, float dy) {
            set.add(dx + "," + dy);
            return this;
        }
    }

    @Test
    void aTimelineToldIsDrawnForTheLaneAndFollowedWhileLive() throws InterruptedException {
        Timeline t = new Timeline("calculator-11", "Inspecting Calculator (calculator-11)", true, 900 * MS,
                List.of(new Span(880 * MS, 890 * MS)));
        shell.bus().publish(Messages.TIMELINE_TOPIC, t);
        eventually("the lane laid out and the timeline drawn", () -> wiring.view().state.pxPerNano() > 0f
                && wiring.view().state.from().rate() == 1.0);
        Strip s = wiring.view().state;
        assertEquals(900 * MS, s.edgeNanos(), "the newest row's moment is where the picture puts the right edge");
        assertEquals(List.of(), s.handovers(), "the first timeline is placed, not glided to");
        assertSame(wiring.ui().strip(), s.strip(), "the handle is the strip in the lane");
    }

    @Test
    void theStripSlidesLeftByHowFarThePresentHasMovedOnSinceItsEdge() {
        Offsets out = new Offsets();
        Strip s = new Strip(null, 1_000 * MS, 0.0001f, Path.following(0L), List.of());
        long due = View.draw(s, new Frame(7L, 1_000 * MS + 250 * MS, false), out);
        assertEquals(List.of("-25000.0,0.0"), out.set, "250 ms on, at 0.1 px a millisecond: 25 px");
        assertEquals(7L, due, "following: the next frame, on the tree's clock");
    }

    @Test
    void stoppedAtTheEdgeTheStripIsWhereLayoutPutItAndNothingIsDue() {
        Offsets out = new Offsets();
        Strip s = new Strip(null, 1_000 * MS, 0.0001f, Path.stopped(1_000 * MS), List.of());
        long due = View.draw(s, new Frame(7L, 5_000 * MS, false), out);
        assertEquals(List.of("0.0,0.0"), out.set);
        assertEquals(Frame.NEVER, due);
    }

    @Test
    void aGlideIsDueUntilItLandsAndUnderReducedMotionGoesStraightThere() {
        Path from = Path.stopped(1_000 * MS);
        Handover back = Glide.handover(from, List.of(), 0L, 200 * MS, Path.stopped(0.0));
        Strip s = new Strip(null, 1_000 * MS, 0.0001f, from, List.of(back));
        Offsets out = new Offsets();
        assertEquals(7L, View.draw(s, new Frame(7L, 100 * MS, false), out), "on its way: due");
        assertEquals("50000.0,0.0", out.set.getLast(), "halfway in time, halfway there: the step is symmetric");
        assertEquals(Frame.NEVER, View.draw(s, new Frame(7L, 200 * MS, false), out), "landed");
        assertEquals("100000.0,0.0", out.set.getLast());
        View.draw(s, new Frame(7L, 1L, true), out);
        assertEquals("100000.0,0.0", out.set.getLast(), "reduced motion: where it is going, at once");
    }

    @Test
    void aPassIsDrawnFromItsStartToItsEndWithTheEdgeMomentAtTheRightEdge() {
        long newest = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, newest, List.of(new Span(newest - 500 * MS, newest - 400 * MS),
                new Span(newest - 100_000L, newest - 99_900L), new Span(newest - 11_000 * MS, newest - 10_500 * MS)));
        // A lane 1000 px wide over 10 s: 0.1 px a millisecond.
        Picture p = View.passes(t, 1000f, 20f, 1000f / View.SPAN_NANOS, Color.WHITE, newest - View.SPAN_NANOS,
                Double.MAX_VALUE);
        List<String> boxes = p.marks().stream().map(m -> (Picture.Fill) m)
                .map(f -> Math.round(f.x()) + "+" + Math.round(f.w())).toList();
        assertEquals(List.of("950+10", "1000+1"), boxes,
                "100 ms is 10 px, a pass shorter than a pixel still a pixel, and one before the range not drawn");
    }
}
