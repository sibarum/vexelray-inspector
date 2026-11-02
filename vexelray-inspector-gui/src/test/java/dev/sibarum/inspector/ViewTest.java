package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Motion;
import dev.sibarum.inspector.Glide.Path;
import dev.sibarum.inspector.View.Strip;
import dev.sibarum.inspector.core.Messages;
import dev.sibarum.inspector.core.Messages.Details;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.RunPanel;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Model;
import dev.sibarum.inspector.core.Bench;
import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
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

        @Override
        public Drawn scale(Node node, float factor) {
            return this;
        }

        @Override
        public Drawn opacity(Node node, float opacity) {
            return this;
        }

        @Override
        public Drawn show(Feed feed, int slot) {
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
                main(pass(880 * MS, 890 * MS)), Optional.empty());
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
    void theRunPanelIsShownWithNothingAttachedAndThenWithTheRunAttached() throws InterruptedException {
        Messages.Run one = new Messages.Run("calculator-11", "Calculator", 11, "uds:one");
        shell.bus().publish(Messages.RUN_PANEL_TOPIC, new RunPanel("", "", 0, "", "", List.of(one), false));
        eventually("nothing attached, said", () -> "No application inspected".equals(wiring.ui().runLine()));
        shell.bus().publish(Messages.RUN_PANEL_TOPIC,
                new RunPanel(one.name(), one.title(), 11, Bench.ATTACHED, "", List.of(one), true));
        eventually("the run attached, said", () -> "Calculator · pid 11 · attached".equals(
                wiring.ui().runLine()));
    }

    @Test
    void aTopicsRowSaysItsEndsByTheirClassesNamesAndWhatCrossedThem() {
        Messages.Flow f = new Messages.Flow("dev.sibarum.inspector.core.Messages.Timeline", "state",
                List.of(new Messages.End("dev.sibarum.inspector.core.Bench", "sends", 10)),
                List.of(new Messages.End("dev.sibarum.inspector.View#timeline", "mailbox", 4),
                        new Messages.End(Messages.End.FRAMEWORK, "", 1)), 10, 5, 59.6);
        assertEquals(List.of("Messages.Timeline", "state", "Bench 10", "View#timeline 4, the framework 1", "10", "5",
                "60"), View.flowCells(f));
        assertEquals("ListRuns", View.flowCells(new Messages.Flow("dev.example.FindRuns", "edge", List.of(),
                List.of(new Messages.End("dev.example.ListRuns", "request", Messages.End.UNCOUNTED)), 1, 0, 0.0)).get(3),
                "a job's request is not counted, and says no number");
        assertEquals("", View.flowCells(new Messages.Flow("dev.example.FindRuns", "edge", List.of(),
                List.of(new Messages.End("dev.example.ListRuns", "request", Messages.End.UNCOUNTED)), 1, 0, 0.0)).get(5),
                "nor does its delivered column");
        assertEquals("", View.flowCells(new Messages.Flow("dev.example.Quiet", "edge", List.of(), List.of(), 0, 0,
                0.0)).getLast(), "no rate said when nothing was written");
    }

    @Test
    void theFlowsTableKeepsItsRowsAsTheCountsMoveAndRebuildsThemForNewTopics() throws InterruptedException {
        Messages.Flow a = new Messages.Flow("dev.example.A", "edge", List.of(), List.of(), 1, 1, 0.0);
        shell.bus().publish(Messages.FLOWS_TOPIC, new Messages.Flows("run", "", List.of(), List.of(a)));
        eventually("a row for the topic", () -> "1".equals(cellOf(wiring.ui(), "dev.example.A", 4)));
        Messages.Flow more = new Messages.Flow("dev.example.A", "edge", List.of(), List.of(), 7, 6, 0.0);
        shell.bus().publish(Messages.FLOWS_TOPIC, new Messages.Flows("run", "a recording declares no bus graph",
                List.of(), List.of(more)));
        eventually("its count moved", () -> "7".equals(cellOf(wiring.ui(), "dev.example.A", 4)));
    }

    private static String cellOf(Ui ui, String topic, int column) {
        List<String> cells = ui.flowCells(topic);
        return cells.isEmpty() ? "" : cells.get(column);
    }

    @Test
    void theRunPanelSaysTheRunItsProcessHowItWasReachedAndItsLogic() {
        Messages.Run one = new Messages.Run("calculator-11", "Calculator", 11, "uds:one");
        assertEquals("Calculator · pid 11 · attached · logic on its own lane, vexel-logic",
                View.runLine(new RunPanel("calculator-11", "Calculator", 11, Bench.ATTACHED,
                        "logic on its own lane, vexel-logic", List.of(one), true)));
        assertEquals("run.csv · replayed", View.runLine(new RunPanel("replay:run.csv", "run.csv", 0,
                Bench.REPLAYED, "", List.of(one), true)), "a recording has no process, and no logic said yet");
        assertEquals("No application inspected", View.runLine(new RunPanel("", "", 0, "", "", List.of(one), false)));
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

    /** Six lanes of 20 px: the main thread's band is 20 to 40. */
    private static final float LANES_H = 120f;

    @Test
    void aPassIsAFillAsLongAsItTookAndAOnePixelLineAtItsEnd() {
        long newest = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, newest, main(pass(newest - 500 * MS, newest - 400 * MS),
                pass(newest - 100_000L, newest - 99_900L), pass(newest - 11_000 * MS, newest - 10_500 * MS)),
                Optional.empty());
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, newest - View.SPAN_NANOS, Double.MAX_VALUE);
        assertEquals(List.of("950+10"), fills(p),
                "100 ms is 10 px; a far shorter pass is its end's line alone, and one before the range not drawn");
        assertEquals(List.of("960 w1", "1000 w1"), lines(p), "the pixel each ends in, a line no zoom stretches");
        Picture.Fill first = (Picture.Fill) p.marks().stream().filter(m -> m instanceof Picture.Fill).findFirst()
                .orElseThrow();
        assertEquals(36.0, first.y() + first.h(), 1e-5, "standing on the main thread's floor, the second, with its margin");
        assertEquals(9.0, first.h(), 1e-5, "100 ms stands three quarters of the band's 12 px reach");
    }

    @Test
    void aLongPassStandsTallAndAStormOfShortOnesLow() {
        assertEquals(View.STATURE_MIN, View.stature(10_000L), "far shorter than the shortest: still a bar");
        assertEquals(1f / 4f, View.stature(1 * MS), 1e-6f);
        assertEquals(1f / 2f, View.stature(10 * MS), 1e-6f);
        assertEquals(1f, View.stature(5_000 * MS), "past a second: the whole reach");
        long at = 20_000 * MS;
        Mark[] storm = new Mark[20];
        for (int i = 0; i < storm.length; i++) {
            storm[i] = pass(at - 1_000 * MS + i * MS, at - 1_000 * MS + (i + 1) * MS);
        }
        Mark[] all = java.util.Arrays.copyOf(storm, storm.length + 1);
        all[storm.length] = pass(at - 700 * MS, at - 680 * MS);
        Timeline t = new Timeline("r", "", true, at, main(all), Optional.empty());
        List<Double> heights = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at).marks().stream()
                .filter(m -> m instanceof Picture.Fill).map(m -> ((Picture.Fill) m).h()).distinct().toList();
        assertEquals(java.util.Set.of(3.0, (double) (12f * View.stature(20 * MS))), java.util.Set.copyOf(heights),
                "twenty 1 ms passes end to end stand a quarter; one 20 ms pass, as long, stands higher");
    }

    @Test
    void theEventTheCursorIsOnIsDrawnOverTheOthersItsBandsHeightWithATwoPixelLine() {
        long at = 20_000 * MS;
        Mark on = pass(at - 100 * MS, at);
        Timeline t = new Timeline("r", "", false, at, main(pass(at - 500 * MS, at - 400 * MS), on),
                Optional.of(new Event(Model.MAIN, on)));
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at);
        assertEquals(List.of("950+10", "990+10", "990+10"), fills(p), "drawn last, so over the others");
        List<String> lines = lines(p);
        assertEquals("999 w2", lines.get(lines.size() - 2), "ending at its end");
        assertEquals("1000 w1", lines.getLast(), "and the cursor across every lane, at the edge moment");
        Picture.Line cursor = (Picture.Line) p.marks().getLast();
        assertEquals(0.0, cursor.y0());
        assertEquals(LANES_H, cursor.y1(), 1e-6);
        Picture.Fill mark = (Picture.Fill) p.marks().get(p.marks().size() - 3);
        assertEquals(20.0, mark.y(), "the top of the main thread's band");
        assertEquals(20.0, mark.h(), "the band's whole height");
        assertEquals(Color.BLACK, mark.color());
    }

    @Test
    void aMomentIsALineAloneInItsLanesBandAndAHeldDrawSaysWhyByItsColour() {
        long at = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, at, List.of(
                new Lane(Model.INPUT, List.of(new Mark(at - 300 * MS, at - 300 * MS, Model.INJECTED))),
                new Lane(Model.DRAWING, List.of(new Mark(at - 200 * MS, at - 200 * MS, Model.HELD_FOR_CEILING),
                        new Mark(at - 100 * MS, at - 100 * MS, Model.HELD_FOR_GPU)))),
                Optional.empty());
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at);
        assertEquals(List.of(), fills(p), "a moment has no length to fill");
        List<Picture.Line> marks = p.marks().stream().filter(m -> m instanceof Picture.Line)
                .map(m -> (Picture.Line) m).filter(l -> l.x0() == l.x1()).toList();
        assertEquals(List.of(4.0, 84.0, 84.0), marks.stream().map(Picture.Line::y0).toList(),
                "input's band first, drawing's fifth");
        float alone = View.Columns.SPARSE;
        assertEquals(List.of(Color.withAlpha(INKS.injected(), alone), Color.withAlpha(INKS.ceiling(), alone),
                Color.withAlpha(INKS.gpu(), alone)), marks.stream().map(Picture.Line::color).toList(),
                "each alone in its pixel, so at a moment's faintest");
    }

    @Test
    void thePathsEventsAreDrawnOverTheLanesInItsColourByCauseAndByNumber() {
        long at = 20_000 * MS;
        Mark tick = new Mark(at - 300 * MS, at - 290 * MS, Model.TICK, "vexel-logic", 9L, "", List.of())
                .with(Model.TICK_NUMBER, "#7", false);
        Mark other = new Mark(at - 200 * MS, at - 190 * MS, Model.TICK, "vexel-logic", 9L, "", List.of())
                .with(Model.TICK_NUMBER, "#8", false);
        Mark sent = new Mark(at - 295 * MS, at - 295 * MS, Model.MESSAGE, "vexel-logic", 9L, "c.i01.1", List.of());
        Timeline t = new Timeline("r", "", false, at, List.of(new Lane(Model.LOGIC, List.of(tick, other)),
                new Lane(Model.MESSAGES, List.of(sent))), Optional.empty());
        Traced traced = new Traced("r", "c.i01.1", List.of(), List.of("#7"), List.of(), List.of());
        Picture p = View.lanes(t, traced, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at, View.SLIDE_LANES, null);
        List<Picture.Mark> path = p.marks().stream().filter(m -> (m instanceof Picture.Fill f && f.color().equals(PATH))
                || (m instanceof Picture.Line l && l.color().equals(PATH))).toList();
        assertEquals(2, path.size(), "tick #7 by its number, the message by its cause; not tick #8: " + path);
        assertEquals(0, View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at).marks().stream()
                .filter(m -> m instanceof Picture.Fill f && f.color().equals(PATH)).count(), "no path, nothing marked");
        assertEquals(List.of("Rows", "Bump"), View.crossedTopics(new Traced("r", "c", List.of(), List.of(), List.of(),
                List.of(new Crossed("Rows", ""), new Crossed("Rows", "B"), new Crossed("Bump", "C")))),
                "each topic once, in the order crossed");
    }

    @Test
    void manyMomentsInAPixelAreOneMarkAsStrongAsTheyAreMany() {
        long at = 20_000 * MS;
        java.util.ArrayList<Mark> burst = new java.util.ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            burst.add(new Mark(at - 499 * MS + i * 500L, at - 499 * MS + i * 500L, Model.MESSAGE));
        }
        burst.add(new Mark(at - 200 * MS, at - 200 * MS, Model.MESSAGE));
        Timeline t = new Timeline("r", "", true, at, List.of(new Lane(Model.MESSAGES, burst)), Optional.empty());
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at);
        List<Picture.Line> marks = p.marks().stream().filter(m -> m instanceof Picture.Line)
                .map(m -> (Picture.Line) m).filter(l -> l.x0() == l.x1()).toList();
        assertEquals(List.of("951 w1", "980 w1"), lines(p), "a thousand moments in half a millisecond, one pixel: one mark");
        assertEquals(1f, marks.getFirst().color().a(), 1e-6f, "dense: full strength");
        assertEquals(View.Columns.SPARSE, marks.getLast().color().a(), 1e-6f, "alone: faintest");
    }

    @Test
    void aMinuteOfMarksIsBoundedByThePictureWidthNotByHowManyThereAre() {
        long at = 80_000 * MS;
        java.util.ArrayList<Mark> passes = new java.util.ArrayList<>();
        java.util.ArrayList<Mark> messages = new java.util.ArrayList<>();
        for (long from = at - 60_000 * MS; from < at; from += 2 * MS) {
            passes.add(pass(from, from + MS + (from / MS % 3) * 10_000L));
            messages.add(new Mark(from, from, Model.MESSAGE));
        }
        Timeline t = new Timeline("r", "", true, at, List.of(new Lane(Model.MAIN, passes),
                new Lane(Model.MESSAGES, messages)), Optional.empty());
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - 60_000 * MS, at);
        assertTrue(p.marks().size() < 2 * 6 * LANE, "60 000 passes and as many messages over six lanes' width drew "
                + p.marks().size() + " marks");
        assertEquals(5, p.marks().stream().filter(m -> m instanceof Picture.Line l && l.y0() == l.y1()).count(),
                "a hairline between each two of the six lanes");
    }

    @Test
    void theStripReachesAsFarAsTheGlideGoes() {
        double span = View.SPAN_NANOS;
        double at = 200_000 * MS;
        assertEquals(View.SLIDE_LANES, View.reach(at, at - 3_000 * MS, at, span, span), "a step: the least reach");
        assertEquals(11, View.reach(at, at - 85_000 * MS, at, span, span),
                "End after 85 s: eight and a half lanes back, a lane for the zoom's widest and one more");
        assertEquals(View.MAX_REACH, View.reach(at, at - 600_000 * MS, at, View.MIN_SPAN_NANOS, View.MIN_SPAN_NANOS),
                "ten minutes at the closest zoom: no further than the most");
    }

    @Test
    void aWideStripIsAPixelACellOnlyWhereAGlideStartsAndLands() {
        View.Cells narrow = View.Cells.about(15_000, LANE, new float[] {8_000f});
        assertEquals(1, narrow.width(100), "the least reach: a pixel a cell throughout");
        int columns = (int) (LANE * (1 + 2 * 60));
        View.Cells wide = View.Cells.about(columns, LANE, new float[] {61_000f, 10_000f});
        assertEquals(1, wide.width(60_500), "the lane at rest where it lands");
        assertEquals(1, wide.width(8_500), "and where it starts");
        assertTrue(wide.width(40_000) > 1, "between, coarser");
        int cells = 0;
        for (int x = 0; x < columns; x = wide.end(x)) {
            cells++;
        }
        assertTrue(cells <= 2 * 3 * LANE + View.Cells.COARSE_CELLS + 2,
                columns + " pixels in " + cells + " cells: three lanes fine about each end, the rest coarse");
        long at = 600_000 * MS;
        java.util.ArrayList<Mark> passes = new java.util.ArrayList<>();
        for (long from = at - 600_000 * MS; from < at; from += 2 * MS) {
            passes.add(pass(from, from + MS + (from / MS % 7) * 300_000L));
        }
        Timeline t = new Timeline("r", "", true, at, main(passes.toArray(Mark[]::new)), Optional.empty());
        Picture p = View.lanes(t, LANE, LANES_H, SCALE, INKS, at - 600_000 * MS, at, 61, at, at - 590_000 * MS);
        assertTrue(p.marks().size() < 20_000, "ten minutes of passes drew " + p.marks().size() + " marks");
    }

    @Test
    void theStripIsNeverWiderThanItsPixelsAllowAndANumberThatIsNotOneReachesTheLeast() {
        assertEquals(130, View.mostReach(1_000f), "a quarter of a million pixels, a thousand a lane");
        assertEquals(View.SLIDE_LANES, View.mostReach(100_000f), "never less than the least");
        assertEquals(View.MAX_REACH, View.mostReach(10f));
        assertEquals(130, View.reach(400.0, 130), "a glide past the most: the most, which the view logs");
        assertEquals(View.SLIDE_LANES, View.reach(View.lanesNeeded(Double.NaN, 0.0, 0.0, 1.0, 1.0), 130));
        assertEquals(View.SLIDE_LANES, View.reach(
                View.lanesNeeded(0.0, Double.NEGATIVE_INFINITY, 0.0, View.SPAN_NANOS, View.SPAN_NANOS), 130));
    }

    @Test
    void aPictureThatWouldOverflowTheWindowIsDrawnCoarserAndSaysSo() {
        long at = 20_000 * MS;
        java.util.ArrayList<Mark> passes = new java.util.ArrayList<>();
        for (long from = at - 150_000 * MS; from < at; from += 10 * MS) {
            // Alternating colours a pixel apart across the whole strip: nothing alike to merge, so a mark a column.
            passes.add(new Mark(from, from + 5 * MS, from / (10 * MS) % 2 == 0 ? Model.PASS : Model.HELD_FOR_GPU));
        }
        Timeline t = new Timeline("r", "", true, at, List.of(new Lane(Model.MAIN, passes), new Lane(Model.LOGIC,
                passes), new Lane(Model.DRAWING, passes)), Optional.empty());
        View.Fit fit = View.fit(t, LANE, LANES_H, SCALE, INKS, at - 150_000 * MS, at, View.SLIDE_LANES);
        assertTrue(fit.wanted() > View.MARK_BUDGET, "drawn a pixel a mark: " + fit.wanted());
        assertTrue(fit.cell() > 1, "so drawn coarser");
        assertTrue(fit.picture().marks().size() <= View.MARK_BUDGET, fit.picture().marks().size() + " marks");
        assertEquals("Lanes drawn in " + fit.cell() + " px cells: more events than the window can draw",
                View.drawingNote(fit.cell(), 0));
        View.Fit fine = View.fit(new Timeline("r", "", true, at, main(pass(at - MS, at)), Optional.empty()), LANE,
                LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at, View.SLIDE_LANES);
        assertEquals(1, fine.cell());
        assertEquals("", View.drawingNote(fine.cell(), 0), "nothing to say");
    }

    @Test
    void anEventEndingBeforeItBeginsIsNotDrawnAndIsCounted() {
        long at = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, at, main(pass(at - 500 * MS, at - 400 * MS),
                pass(at - 300 * MS, at - 350 * MS)), Optional.empty());
        assertEquals(1, View.malformed(t));
        assertEquals(List.of("950+10"), fills(View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at)));
        assertEquals(List.of("960 w1"), lines(View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at)));
        assertEquals("1 event not drawn: it ends before it begins", View.drawingNote(1, 1));
        assertEquals("Lanes drawn in 4 px cells: more events than the window can draw.  2 events not drawn: they end"
                + " before they begin", View.drawingNote(4, 2));
    }

    @Test
    void theFocusedLaneIsWashedAndOutlinedOverItsOwnBand() {
        Picture p = View.focusGlow(LANE, LANES_H, Model.LANES.indexOf(Model.LOGIC), Color.WHITE);
        Picture.Fill wash = (Picture.Fill) p.marks().getFirst();
        assertEquals(40.0, wash.y(), "the logic lane, the third band");
        assertEquals(20.0, wash.h());
        assertEquals(LANE, wash.w(), 1e-6);
        assertTrue(wash.color().a() < 0.1f, "faint, so the marks under it still read");
        assertEquals(4, p.marks().size(), "the wash, and three rings fading inward");
    }

    @Test
    void theLanesStayPutWhileTheCursorIsInViewAndOtherwisePutItInTheMiddle() {
        double span = View.SPAN_NANOS;
        double rest = 50_000 * MS;
        assertEquals(rest, View.keepInView(rest, rest - 3_000 * MS, span), "a click well inside: nothing moves");
        assertEquals(rest, View.keepInView(rest, rest - 9_400 * MS, span), "just clear of the left side");
        assertEquals(rest - 9_600 * MS + span / 2, View.keepInView(rest, rest - 9_600 * MS, span),
                "inside the left margin: glide it to the middle");
        assertEquals(rest + 2_000 * MS + span / 2, View.keepInView(rest, rest + 2_000 * MS, span),
                "past the right side, as after a step forward");
        assertEquals(rest + span / 2 - 200 * MS, View.keepInView(rest, rest - 200 * MS, span),
                "inside the right margin too");
    }

    @Test
    void whileFollowingNoCursorIsDrawn() {
        long at = 20_000 * MS;
        Timeline t = new Timeline("r", "", true, at, main(pass(at - 500 * MS, at - 400 * MS)), Optional.empty());
        assertEquals(List.of("960 w1"), lines(View.lanes(t, LANE, LANES_H, SCALE, INKS, at - View.SPAN_NANOS, at)));
    }

    @Test
    void theDetailsAreTheClockAndARowForEachFieldTheEventHas() {
        ZoneId utc = ZoneId.of("UTC");
        long wall = Instant.parse("2026-10-31T03:32:17.456968Z").getEpochSecond() * 1_000_000_000L + 456_968_123L;
        Mark m = new Mark(110 * MS, 110 * MS, Model.MESSAGE, "vexel-source-link-rows", 64L, "76673.o00.6c", List.of(
                new Field(Model.TYPE, "Messages.Rows"),
                new Field(Model.PATH, "I/O LinkRows, firing 108 → published Messages.Rows"),
                new Field(Model.PASS_NUMBER, "#64"), new Field(Model.THREAD, "vexel-source-link-rows"),
                new Field(Model.PACKAGE, "dev.sibarum.inspector.core")));
        List<String> said = View.details(new Details("r", 110 * MS, 6_230 * MS, wall,
                Optional.of(new Event(Model.MESSAGES, m))), utc);
        assertEquals(View.DETAILS.size(), said.size(), "the clock, and a row for every field of the schema");
        assertEquals("03:32:17.456968   2026-10-31T03:32:17.456968Z   6.230 s into the run", said.get(0));
        assertEquals("Messages.Rows", said.get(View.DETAILS.indexOf(Model.TYPE)));
        assertEquals("I/O LinkRows, firing 108\n→ published Messages.Rows",
                said.get(View.DETAILS.indexOf(Model.PATH)), "a hop a line");
        assertEquals("dev.sibarum.inspector.core", said.get(View.DETAILS.indexOf(Model.PACKAGE)));
        assertEquals("", said.get(View.DETAILS.indexOf(Model.TOOK)), "a moment took no time: its row is hidden");
        assertEquals("", said.get(View.DETAILS.indexOf(Model.DREW)), "a pass's field: hidden for a message");
    }

    @Test
    void theLanesARunDoesNotRecordAreNamedWithHowToRecordThem() {
        Timeline t = new Timeline("r", "", true, 0L, List.of(new Lane(Model.INPUT, List.of()),
                new Lane(Model.MAIN, List.of()), new Lane(Model.LOGIC, List.of(), false),
                new Lane(Model.GPU, List.of(), false)), Optional.empty());
        assertEquals(List.of(Model.LOGIC, Model.GPU), View.unrecorded(t));
        assertEquals("Not recorded by this run: Logic and GPU. Start it with --automation=0, which records every lane",
                View.lanesNote(View.unrecorded(t)));
        assertEquals("Not recorded by this run: Logic, Messages and GPU. Start it with --automation=0, which records every lane",
                View.lanesNote(List.of(Model.LOGIC, Model.MESSAGES, Model.GPU)));
        assertEquals("", View.lanesNote(List.of()), "all recorded: nothing to say");
    }

    @Test
    void withNoEventOnlyTheClockAndThatThereIsNoneAreSaid() {
        List<String> said = View.details(new Details("r", 1L, 0L, -1L, Optional.empty()), ZoneId.of("UTC"));
        assertEquals("not said by the run   0.000 s into the run", said.get(0));
        assertEquals(View.NO_EVENT, said.get(View.DETAILS.indexOf(Model.TYPE)));
        assertEquals(2, said.stream().filter(s -> !s.isEmpty()).count());
        assertTrue(View.details(new Details("", 0L, 0L, -1L, Optional.empty()), ZoneId.of("UTC")).stream()
                .allMatch(String::isEmpty), "nothing attached");
    }

    @Test
    void theReadoutsAreOneLineOfNumbers() {
        assertEquals("Draws 57/s, held 3 for the ceiling and 0 for the GPU   Ticks 120/s, p50 0.45 ms, p95 1.20 ms"
                        + "   Rows lost 0",
                View.readout(new Readouts("r", 57, 3, 0, 120, 450_000, 1_200_000, 0)));
        assertEquals("", View.readout(new Readouts("", 0, 0, 0, 0, 0, 0, 0)), "nothing attached and nothing read");
    }

    @Test
    void aClickGoesToTheEventNearestItWithinReach() {
        List<Mark> passes = List.of(pass(100, 200), pass(300, 310), pass(1_000, 1_001));
        assertEquals(Optional.of(pass(100, 200)), View.nearest(passes, 150, 5), "on it");
        assertEquals(Optional.of(pass(300, 310)), View.nearest(passes, 260, 50), "the nearer of two");
        assertEquals(Optional.of(pass(1_000, 1_001)), View.nearest(passes, 1_004, 5), "just past a short one");
        assertEquals(Optional.empty(), View.nearest(passes, 600, 50), "none within reach");
    }

    private static float offset(Offsets out) {
        return Float.parseFloat(out.set.getLast().split(",")[0]);
    }

    /** White marks, a black cursor, and a colour of its own for each other kind, and for a path, to tell them apart. */
    private static final Color PATH = Color.rgb(1, 1, 0);
    private static final View.Inks INKS = new View.Inks(Color.WHITE, Color.rgb(1, 0, 0), Color.rgb(0, 1, 0),
            Color.rgb(0, 0, 1), Color.BLACK, Color.rgb(0.5f, 0.5f, 0.5f), PATH);

    private static Mark pass(long from, long to) {
        return new Mark(from, to, Model.PASS);
    }

    /** A timeline's lanes with only the main thread's holding anything. */
    private static List<Lane> main(Mark... passes) {
        return List.of(new Lane(Model.MAIN, List.of(passes)));
    }

    /** Each fill's left edge, relative to the lane's left at rest, and its width, rounded. */
    private static List<String> fills(Picture p) {
        return p.marks().stream().filter(m -> m instanceof Picture.Fill).map(m -> (Picture.Fill) m)
                .map(f -> Math.round(f.x() + REST) + "+" + Math.round(f.w())).toList();
    }

    /** Each mark's line (upright, unlike the hairlines between lanes): its x, as {@link #fills}, and thickness. */
    private static List<String> lines(Picture p) {
        return p.marks().stream().filter(m -> m instanceof Picture.Line).map(m -> (Picture.Line) m)
                .filter(l -> l.x0() == l.x1())
                .map(l -> Math.round(l.x0() + REST) + " w" + Math.round(l.thickness())).toList();
    }
}
