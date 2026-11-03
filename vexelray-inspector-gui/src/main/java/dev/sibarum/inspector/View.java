package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Handover;
import dev.sibarum.inspector.Glide.Motion;
import dev.sibarum.inspector.Glide.Path;
import dev.sibarum.inspector.Keys.EventKey;
import dev.sibarum.inspector.Keys.FollowKey;
import dev.sibarum.inspector.Keys.RowKey;
import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.Keys.StepKey;
import dev.sibarum.inspector.Keys.ZoomKey;
import dev.sibarum.inspector.core.Messages.Account;
import dev.sibarum.inspector.core.Messages.Choose;
import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Messages.Difference;
import dev.sibarum.inspector.core.Messages.Details;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.Follow;
import dev.sibarum.inspector.core.Messages.GoTo;
import dev.sibarum.inspector.core.Messages.GoToRow;
import dev.sibarum.inspector.core.Messages.GoToTick;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.LedgerAt;
import dev.sibarum.inspector.core.Messages.Leave;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.ReplayReport;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.RowsAround;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.RunAgain;
import dev.sibarum.inspector.core.Messages.RunPanel;
import dev.sibarum.inspector.core.Messages.Seek;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.StepEvent;
import dev.sibarum.inspector.core.Messages.StepRow;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.sibarum.inspector.core.Model;
import dev.sibarum.inspector.core.Bench;
import dev.vexelray.framework.api.Draw;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.canvas.Color;
import sibarum.atchung.Sender;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The view: what the bench decided, shown. A {@code @View}, so it runs on the GUI thread, in each frame's widget tick
 * (threading.md, T7.4), and it decides nothing: every decision is the {@code Bench}'s, in {@code -core}.
 *
 * <h2>Told builds, asked chooses</h2>
 *
 * <p>The timeline is the frame contract's first application witness (vexelray-gui's
 * {@code docs/plans/frame-contract.md}; vexelray-inspector's {@code docs/design.md} 9.1). Its marks arrive a batch at
 * a time, and the moment at its right edge moves continuously, so the two are drawn by the two halves:
 *
 * <ul>
 *   <li><b>Told</b> ({@link #timeline}, and a new size of the lane): the strip's picture is built from the passes,
 *       placed against the timeline's edge moment, and the motion is decided: if the bench now says to follow the
 *       present, or to stop somewhere, a {@link Handover} from the motion in progress to that is added. The view's one
 *       state, a {@link Strip}, is replaced. Allocating is fine here: it happens when something changed.</li>
 *   <li><b>Asked</b> ({@link #draw}, every frame while anything moves): where the motion puts the right edge at the
 *       frame's present, and the strip slid by how far that is from the picture's edge moment. It reads the state and
 *       the frame and allocates nothing, which the processor checks.</li>
 * </ul>
 *
 * <p><b>No jumps.</b> The edge never moves in one frame and never has a corner in its speed or any derivative of it
 * (design.md 9.8): following, stopping, stepping, following again, and each interrupted, are handovers between smooth
 * paths ({@link Glide}). Under reduced motion it goes straight to where it is going.
 *
 * <p><b>On the machine's clock.</b> The rows' moments are on {@code System.nanoTime()}'s clock (the link source set
 * them there), and so is {@link Frame#monoNanos}, so the strip is placed by subtracting the one from the other. The
 * told half reads the same clock directly (framework-notes FN-5). The frame's {@code presentNanos} is the tree's
 * logical clock: it is used only to say when the next frame is due.
 *
 * <h2>Keys</h2>
 *
 * <p>Space pauses and follows again, End (and the button at the focused lane's right end) only follows again, the arrows step the cursor onto the next or previous event of the focused lane,
 * Shift with them steps a tenth of the lane, and {@code =} and {@code -} zoom about the right edge: a second motion, of
 * the time the lane spans in doublings, which the asked half draws as a stretch of the picture ({@code Drawn.stretch})
 * until the next picture is built at the new scale. A click on a lane focuses it, and puts the cursor on the event
 * nearest the click if one is near ({@link GoTo}), or else at the moment clicked ({@link Seek}); the main thread's is
 * focused at first.
 *
 * <p><b>The cursor is a line, not the right edge.</b> Stopped, the cursor is drawn across the lanes where its moment
 * is, and the lanes stay where they came to rest for as long as it is in view: a click leaves it where it was clicked,
 * and a step moves the line. Only a step that would take it within {@link #VIEW_MARGIN} of either side glides the lanes
 * to put it in the middle ({@link #keepInView}). Space is the exception: it stops the cursor where the lanes come to
 * rest, so it is at their right edge, where the present was. Which lane is focused is the view's own:
 * it is how the timeline is looked at, not what the run did, so it is not the bench's and does not rewind (design.md
 * 9.7). Each chord is declared with the message it sends ({@link PauseKey}, {@link EventKey},
 * {@link StepKey}), and the view turns it into the bench's {@link Pause}, {@link StepEvent} or {@link Step} with the
 * moment the lane would come to rest at, which only the view knows. The handover starts at the key, though it is added
 * when the bench answers, a tick later: the step is still flat there in every derivative, so starting it a few
 * milliseconds back moves nothing that can be seen.
 *
 * <p><b>The lanes</b> are bands of one picture, in {@code Model.LANES}' order, since they share the time axis and
 * slide together. A stretch (a pass, a tick, a draw, a wait) is drawn as the passes always were; a moment (an input, a
 * draw held) is the line alone. A draw held for the ceiling is drawn faint, since that is pacing doing its job; one
 * held for the GPU in the caution colour, since that is the GPU being slow; injected input dimmed beside input taken
 * from a device.
 *
 * <p><b>The event the cursor is on</b> is drawn over the others in the text's colour, the whole height of its lane's
 * band and at least two pixels wide, so that it can be told from its neighbours even where a pass is far narrower than
 * a pixel.
 */
@dev.vexelray.framework.api.View
final class View {

    private static final Log LOG = Log.of("vexelray-inspector.view");

    /** How much time the lane spans at first, right edge to left: the bench's reach. */
    static final long SPAN_NANOS = 10_000_000_000L;

    /** The least and most the lane can span: passes milliseconds apart a lane apart, and all the tape keeps. */
    static final long MIN_SPAN_NANOS = 10_000_000L;
    static final long MAX_SPAN_NANOS = 60_000_000_000L;

    /** How long a zoom by one press takes: as long as a move of this many pixels. */
    static final double ZOOM_AS_PX = 120.0;

    /**
     * The fewest lanes the strip reaches past the lane on each side: room for the longest glide the kept minute allows
     * at the first zoom (six lanes of ten seconds), and one more for the present moving on during it. A longer glide,
     * as from an island long kept, widens the strip for as long as it lasts ({@link #reach}).
     */
    static final int SLIDE_LANES = 7;

    /** How close a click must be to an event to go to it, in pixels; further, it puts the cursor where it landed. */
    static final float CLICK_REACH_PX = 6f;

    /** How near either side of the lanes the cursor may come before they glide to put it in the middle: of the span. */
    static final double VIEW_MARGIN = 0.05;

    /**
     * The view's state, which its {@link #draw} reads.
     *
     * @param strip        the node the marks are drawn on, a handle
     * @param edgeNanos    the moment the picture puts at the lane's right edge, on the machine's clock
     * @param pictureScale the pixels a nanosecond the picture was built at
     * @param laneW        the lane's width
     * @param reach        how many of it the strip reaches past the lane each side, {@link #SLIDE_LANES} or more
     * @param edge         where the lane's right edge is, a moment, as a function of the present
     * @param zoom         how much time the lane spans, as its natural logarithm, so that each doubling takes as long
     */
    record Strip(Node strip, long edgeNanos, float pictureScale, float laneW, int reach, Motion edge, Motion zoom) {

        /** A strip at the least reach. */
        Strip(Node strip, long edgeNanos, float pictureScale, float laneW, Motion edge, Motion zoom) {
            this(strip, edgeNanos, pictureScale, laneW, SLIDE_LANES, edge, zoom);
        }
    }

    /**
     * The most lanes the strip reaches past the lane each side: a glide longer than this, as at the closest zoom after a
     * long pause, still empties the lanes for its middle frames, which go by too fast to read.
     */
    static final int MAX_REACH = 256;

    /**
     * The colours the lanes are drawn in: a stretch or a moment, a draw held for the ceiling, one held for the GPU,
     * injected input, the event the cursor is on, the hairlines between the lanes, and the rest of that event's path.
     */
    record Inks(Color mark, Color ceiling, Color gpu, Color injected, Color on, Color line, Color path) {

        /** What {@code kind} is drawn in. */
        Color of(String kind) {
            return kind.equals(Model.HELD_FOR_CEILING) ? ceiling : kind.equals(Model.HELD_FOR_GPU) ? gpu
                    : kind.equals(Model.INJECTED) ? injected : mark;
        }
    }

    /** The one state the asked half reads: replaced when the timeline, the motion or the lane changes. */
    Strip state;

    private final Gui gui;
    private final Ui ui;
    private final Inks inks;
    private final Sender<Pause> pause;
    private final Sender<Follow> follow;
    private final Sender<Step> step;
    private final Sender<StepEvent> stepEvent;
    private final Sender<GoTo> goTo;
    private final Sender<StepRow> stepRow;
    private final Sender<GoToRow> goToRow;
    private final Sender<Seek> seek;
    private final Sender<Choose> choose;
    private final Sender<Leave> leave;
    private final Sender<RunAgain> runAgain;
    private final Sender<GoToTick> goToTick;

    /** What the bench last said, kept to draw again at a new size or zoom. */
    private Timeline last = new Timeline("", "", false, 0L, List.of(), Optional.empty());
    private float laneX;
    private float laneY;
    private float laneW;
    private float laneH;

    /** The lane the arrows step through, one of {@code Model.LANES}. */
    private String focus = Model.MAIN;

    /** How much time the lane spans, or is zooming to. */
    private double span = SPAN_NANOS;

    /** Whether the last picture fell short of its glide, was drawn coarser, or left events out: logged as each starts. */
    private boolean glideCut;
    private boolean coarsened;
    private int malformed;

    /** When the last key was pressed, so the motion it causes starts then; and, for a pause, the window it chose. */
    private long pressedAt = -1L;
    private long pressWindow = -1L;

    View(Gui gui, Ui ui, Sender<PauseKey> pauseKey, Sender<FollowKey> followKey, Sender<EventKey> eventKey,
         Sender<StepKey> stepKey, Sender<ZoomKey> zoomKey, Sender<RowKey> rowKey, Sender<Pause> pause,
         Sender<Follow> follow, Sender<StepEvent> stepEvent, Sender<Step> step, Sender<GoTo> goTo, Sender<Seek> seek,
         Sender<Choose> choose, Sender<Leave> leave, Sender<StepRow> stepRow, Sender<GoToRow> goToRow,
         Sender<RunAgain> runAgain, Sender<GoToTick> goToTick) {
        this.gui = gui;
        this.ui = ui;
        this.pause = pause;
        this.follow = follow;
        this.step = step;
        this.stepEvent = stepEvent;
        this.goTo = goTo;
        this.stepRow = stepRow;
        this.goToRow = goToRow;
        this.seek = seek;
        this.choose = choose;
        this.leave = leave;
        this.runAgain = runAgain;
        this.goToTick = goToTick;
        this.inks = new Inks(gui.theme().color(Role.ACCENT), gui.theme().color(Role.FAINT),
                gui.theme().color(Role.CAUTION), gui.theme().color(Role.DIM), gui.theme().color(Role.INK),
                gui.theme().color(Role.LINE), gui.theme().color(Ui.PATH));
        this.state = new Strip(ui.strip(), 0L, 0f, 0f, Motion.of(Path.stopped(0.0)),
                Motion.of(Path.stopped(Math.log(span))));
        this.diagramInks = new Diagram.Inks(gui.theme().color(Role.LINE), gui.theme().color(Role.INK),
                gui.theme().color(Role.ACCENT), gui.theme().color(Role.LINE), gui.theme().color(Role.DIM),
                gui.theme().color(Ui.PATH));
        gui.hear(ui.graph(), NodeEvent.RESIZE, layout -> {
            graphW = layout.rect().w();
            graphH = layout.rect().h();
            ui.graph().picture(Diagram.draw(lastFlows, traced, graphW, graphH, diagramInks));
        });
        gui.hear(ui.lane(), NodeEvent.RESIZE, layout -> {
            laneX = layout.rect().x();
            laneY = layout.rect().y();
            laneW = layout.rect().w();
            laneH = layout.rect().h();
            showFocus();
            show();
        });
        gui.hear(ui.lane(), NodeEvent.CLICK, click -> clicked(click.x(), click.y()));
        gui.shortcut(Shortcut.of(Key.SPACE), pauseKey, new PauseKey());
        gui.shortcut(Shortcut.of(Key.END), followKey, new FollowKey());
        gui.shortcut(Shortcut.of(Key.LEFT), eventKey, new EventKey(-1));
        gui.shortcut(Shortcut.of(Key.RIGHT), eventKey, new EventKey(1));
        gui.shortcut(Shortcut.of(Key.LEFT, Modifier.SHIFT), stepKey, new StepKey(-1));
        gui.shortcut(Shortcut.of(Key.RIGHT, Modifier.SHIFT), stepKey, new StepKey(1));
        gui.shortcut(Shortcut.of(Key.UP), rowKey, new RowKey(-1));
        gui.shortcut(Shortcut.of(Key.DOWN), rowKey, new RowKey(1));
        gui.shortcut(Shortcut.of(Key.EQUAL), zoomKey, new ZoomKey(true));
        gui.shortcut(Shortcut.of(Key.MINUS), zoomKey, new ZoomKey(false));
    }

    // ------------------------------------------------------------------ keys and clicks

    /** Space: ask the bench to stop where the lane would come to rest from now, or, stopped, to follow again. */
    @Subscribe
    public void pauseKey(PauseKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = Glide.window(0.0);
        pause.send(new Pause((long) state.edge().rest(now, pressWindow)));
    }

    /** End: ask the bench to follow the present again. */
    @Subscribe
    public void followKey(FollowKey key) {
        followed();
    }

    /** The button at the focused lane's right end: as End. */
    @Subscribe
    public void pressed(Button.Pressed p) {
        if (p.button().equals(ui.followAddress())) {
            followed();
        } else if (p.button().equals(ui.detachAddress())) {
            leave.send(new Leave());
        } else if (p.button().equals(ui.runAgainAddress())) {
            runAgain.send(new RunAgain());
        }
    }

    /** A run picked in the run panel: ask the bench to inspect it. */
    @Subscribe
    public void picked(Select.Committed c) {
        if (c.select().equals(ui.runsAddress())) {
            for (Object chosen : c.chosen()) {
                if (chosen instanceof Run run) {
                    choose.send(new Choose(run, Bench.ATTACHED));
                }
            }
        }
    }

    private void followed() {
        pressedAt = System.nanoTime();
        pressWindow = -1L;
        follow.send(new Follow());
    }

    /** Shift and an arrow: ask the bench to step the cursor a tenth of the lane, from where the lane would rest. */
    @Subscribe
    public void stepKey(StepKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = -1L;
        step.send(new Step(key.direction() * (long) (span / 10), (long) state.edge().rest(now, Glide.window(0.0))));
    }

    /** An arrow: ask the bench to step the cursor onto the focused lane's next or previous event. */
    @Subscribe
    public void eventKey(EventKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = -1L;
        stepEvent.send(new StepEvent(focus, key.by(), (long) state.edge().rest(now, Glide.window(0.0))));
    }

    /** Up or Down: ask the bench to step the cursor onto the next or previous row, from where the lane would rest. */
    @Subscribe
    public void rowKey(RowKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = -1L;
        stepRow.send(new StepRow(key.by(), (long) state.edge().rest(now, Glide.window(0.0))));
    }

    /** A click on a row of the rows panel: ask the bench to put the cursor on the row shown there. */
    @Subscribe
    public void rowClicked(ListView.Clicked c) {
        if (c.list().equals(ui.rowsAddress()) && c.item() instanceof Integer place && place < rowsAround.rows().size()) {
            pressedAt = System.nanoTime();
            pressWindow = -1L;
            goToRow.send(new GoToRow(rowsAround.rows().get(place)));
        }
        if (c.list().equals(ui.replayAddress()) && c.item() instanceof Integer place
                && place < replayReport.differences().size()) {
            pressedAt = System.nanoTime();
            pressWindow = -1L;
            goToTick.send(new GoToTick(replayReport.differences().get(place).tick()));
        }
    }

    /** {@code =} or {@code -}: halve or double what the lane spans, about its right edge, gliding there. */
    @Subscribe
    public void zoomKey(ZoomKey key) {
        span = Math.clamp(key.in() ? span / 2 : span * 2, MIN_SPAN_NANOS, MAX_SPAN_NANOS);
        long now = System.nanoTime();
        Path to = Path.stopped(Math.log(span));
        Motion zoom = state.zoom().settled(now);
        if (gui.reducedMotion()) {
            zoom = Motion.of(to);
        } else if (!zoom.goingTo(to)) {
            zoom = zoom.then(now, Glide.window(ZOOM_AS_PX), to);
        }
        Motion edge = state.edge().settled(now);
        if (!last.live() && edge.last().rate() == 0.0) {
            // Stopped: a zoom about the right edge must not lose the cursor, so it is kept in view as a step keeps it.
            double rest = edge.rest(now, Glide.window(0.0));
            double keep = keepInView(rest, last.edgeNanos(), span);
            if (keep != rest) {
                edge = gui.reducedMotion() ? Motion.of(Path.stopped(keep))
                        : edge.then(now, Glide.window(ZOOM_AS_PX), Path.stopped(keep));
            }
        }
        state = new Strip(state.strip(), state.edgeNanos(), state.pictureScale(), state.laneW(), state.reach(), edge,
                zoom);
        show();
    }

    /**
     * A click on the lanes: focus the lane it is in, and put the cursor on its event nearest the click, if one is
     * within {@link #CLICK_REACH_PX}, or else at the moment clicked.
     */
    private void clicked(float x, float y) {
        long now = System.nanoTime();
        double scale = scale(now);
        if (scale <= 0 || laneH <= 0f) {
            return;
        }
        int band = Math.clamp((int) ((y - laneY) / (laneH / Model.LANES.size())), 0, Model.LANES.size() - 1);
        focus = Model.LANES.get(band);
        ui.names(focus, unrecorded(last));
        showFocus();
        double at = state.edge().at(now) - (laneX + laneW - x) / scale;
        pressedAt = now;
        pressWindow = -1L;
        Optional<Mark> near = nearest(marks(last, focus), at, CLICK_REACH_PX / scale);
        if (near.isPresent()) {
            goTo.send(new GoTo(new Event(focus, near.get())));
        } else {
            seek.send(new Seek((long) at));
        }
    }

    /** Mark the focused lane's band over the lanes, where the strip's slide does not move it. */
    private void showFocus() {
        if (laneH > 0f) {
            ui.lane().overlay(focusGlow(laneW, laneH, Model.LANES.indexOf(focus), inks.mark()));
        }
    }

    /**
     * The focused lane's mark, over lanes {@code w} by {@code h}, of which it is the {@code index}th band: a faint wash
     * of {@code c} across the band and a one-pixel outline of it, with two fainter rings inside that fade into the
     * wash, which reads as a glow. Pure, for a test.
     */
    static Picture focusGlow(float w, float h, int index, Color c) {
        float band = h / Model.LANES.size();
        float y = band * index;
        return new Sketch().tag("focus")
                .fill(0f, y, w, band, Color.withAlpha(c, 0.06f))
                .outline(2f, y + 2f, w - 4f, band - 4f, 1f, Color.withAlpha(c, 0.12f))
                .outline(1f, y + 1f, w - 2f, band - 2f, 1f, Color.withAlpha(c, 0.3f))
                .outline(0f, y, w, band, 1f, Color.withAlpha(c, 0.85f))
                .picture();
    }

    /** The events of {@code t}'s lane named {@code name}; none before the bench has said anything. */
    private static List<Mark> marks(Timeline t, String name) {
        for (Lane l : t.lanes()) {
            if (l.name().equals(name)) {
                return l.marks();
            }
        }
        return List.of();
    }

    /** The event nearest {@code atNanos}, if one is within {@code reachNanos} of it. Pure, for a test. */
    static Optional<Mark> nearest(List<Mark> marks, double atNanos, double reachNanos) {
        Mark best = null;
        double bestGap = reachNanos;
        for (Mark p : marks) {
            double gap = atNanos < p.fromNanos() ? p.fromNanos() - atNanos
                    : atNanos > p.toNanos() ? atNanos - p.toNanos() : 0.0;
            if (gap <= bestGap) {
                best = p;
                bestGap = gap;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The pixels a nanosecond the lane is drawn at now: its width over the span the zoom has it at. */
    private double scale(long now) {
        return laneW > 0f ? laneW / Math.exp(state.zoom().at(now)) : 0.0;
    }

    // ------------------------------------------------------------------ the bench's timeline

    /** The bench's timeline: say what it says, move to where it says, and draw it. */
    @Subscribe
    public void timeline(Timeline t) {
        long now = System.nanoTime();
        Motion edge = state.edge().settled(now);
        boolean fresh = last.lanes().stream().allMatch(l -> l.marks().isEmpty())
                || (!t.run().isEmpty() && !t.run().equals(last.run()));
        Path to = t.live() ? Path.following(now) : Path.stopped(stopAt(t, edge, now));
        if (fresh || gui.reducedMotion()) {
            edge = Motion.of(to);
        } else if (!edge.goingTo(to)) {
            long start = pressedAt >= 0 ? pressedAt : now;
            double distancePx = (to.at(now) - edge.at(now)) * scale(now);
            long window = Glide.window(distancePx);
            if (pressWindow >= 0 && to.rate() == 0.0) {
                // A pause stops where the key reckoned the lane would rest, over the window it reckoned with.
                window = pressWindow;
            } else if (edge.last().rate() != 0.0 && to.rate() == 0.0
                    && to.value() == edge.rest(now, Glide.window(0.0))) {
                // Coasting to where it would rest from now, over the window that was reckoned with.
                start = now;
                window = Glide.window(0.0);
            }
            edge = edge.then(start, window, to);
        }
        pressedAt = -1L;
        pressWindow = -1L;
        last = t;
        state = new Strip(state.strip(), state.edgeNanos(), state.pictureScale(), state.laneW(), state.reach(), edge,
                state.zoom().settled(now));
        ui.status(t.status());
        ui.warning(t.warning());
        ui.canFollow(!t.live());
        List<String> unrecorded = unrecorded(t);
        ui.names(focus, unrecorded);
        ui.lanesNote(lanesNote(unrecorded));
        show();
    }

    /** The names of {@code t}'s lanes the run's probe does not record. Pure, for a test. */
    static List<String> unrecorded(Timeline t) {
        return t.lanes().stream().filter(l -> !l.recorded()).map(Lane::name).toList();
    }

    /**
     * What the status line says of the lanes in {@code unrecorded}: that the run does not record them, and how to start
     * one that does; empty when there are none. Pure, for a test.
     */
    static String lanesNote(List<String> unrecorded) {
        if (unrecorded.isEmpty()) {
            return "";
        }
        String names = unrecorded.size() == 1 ? unrecorded.getFirst()
                : String.join(", ", unrecorded.subList(0, unrecorded.size() - 1)) + " and " + unrecorded.getLast();
        return "Not recorded by this run: " + names + ". Start it with --automation=0, which records every lane";
    }

    /**
     * Where the lanes' right edge stops. Space put the cursor where the lanes would rest, so they rest there, the cursor
     * at their right edge. Otherwise they come to rest where they would if left alone (coasting to a stop, if they were
     * following) and stay there while that keeps the cursor in view ({@link #keepInView}): a click leaves the cursor
     * where it was clicked, and a step moves the cursor, not the lanes, until it would leave them.
     */
    private double stopAt(Timeline t, Motion edge, long now) {
        if (pressWindow >= 0) {
            return t.edgeNanos();
        }
        double rest = edge.rest(now, Glide.window(0.0));
        if (pressedAt < 0 && edge.last().rate() != 0.0) {
            return rest;    // the run went away while followed: coast to a stop
        }
        return keepInView(rest, t.edgeNanos(), span);
    }

    /**
     * Where the right edge rests to show {@code cursorNanos}: at {@code restNanos} if the cursor is inside the lanes
     * spanning {@code spanNanos} back from there, clear of either side by {@link #VIEW_MARGIN} of the span; otherwise
     * where it puts the cursor in the middle. Pure, for a test.
     */
    static double keepInView(double restNanos, double cursorNanos, double spanNanos) {
        double margin = spanNanos * VIEW_MARGIN;
        if (cursorNanos <= restNanos - margin && cursorNanos >= restNanos - spanNanos + margin) {
            return restNanos;
        }
        return cursorNanos + spanNanos / 2;
    }

    /**
     * Build the strip's picture at the scale the zoom is going to, over every moment the motion will pass through, and
     * the state the asked half draws it by.
     */
    private void show() {
        if (laneW <= 0f) {
            return;     // not laid out yet: the lane's size arrives and draws it
        }
        long now = System.nanoTime();
        float scale = (float) (laneW / span);
        Motion edge = state.edge();
        if (!Double.isFinite(edge.at(now))) {
            // Nothing the view does should get here; if it does, start again from what the bench said rather than
            // slide the strip by a number that is not one, which would leave the lanes blank with no word why.
            LOG.error("the lanes' motion put the right edge at {}; starting again from the timeline's edge",
                    edge.at(now));
            edge = Motion.of(last.live() ? Path.following(now) : Path.stopped(last.edgeNanos()));
        }
        double[] rests = rests(edge, now);
        double lo = Arrays.stream(rests).min().orElse(last.edgeNanos());
        double hi = Arrays.stream(rests).max().orElse(last.edgeNanos());
        double widest = Math.max(span, Math.exp(state.zoom().at(now)));
        int most = mostReach(laneW);
        double needed = lanesNeeded(last.edgeNanos(), lo, hi, span, widest);
        int reach = reach(needed, most);
        if ((needed > most) != glideCut) {
            glideCut = needed > most;
            if (glideCut) {
                LOG.warn("a glide across {} lanes is longer than the strip can reach ({} each side); its middle frames"
                        + " show no marks", (int) Math.ceil(needed), most);
            }
        }
        if (edge.last().rate() != 0.0) {
            hi = Double.MAX_VALUE;
        }
        ui.reach(reach);
        Fit fit = fit(last, traced, laneW, laneH, scale, inks, lo - widest, hi, reach, rests);
        ui.strip().picture(fit.picture());
        tell(fit, malformed(last));
        state = new Strip(ui.strip(), last.edgeNanos(), scale, laneW, reach, edge, state.zoom());
    }

    /**
     * Log and show what the picture could not draw as it should: coarser than a pixel, or events that end before they
     * begin. Logged when it starts, not on every picture, and shown in the status line's drawing slot for as long as
     * it lasts.
     */
    private void tell(Fit fit, int malformed) {
        if ((fit.cell() > 1) != coarsened) {
            coarsened = fit.cell() > 1;
            if (coarsened) {
                LOG.warn("the lanes needed {} marks, more than the {} a window can draw; drawn in cells of {} px",
                        fit.wanted(), MARK_BUDGET, fit.cell());
            } else {
                LOG.info("the lanes are drawn a pixel a mark again");
            }
        }
        if ((malformed > 0) != (this.malformed > 0)) {
            if (malformed > 0) {
                LOG.warn("{} events end before they begin; they are not drawn", malformed);
            } else {
                LOG.info("no event ends before it begins any more");
            }
        }
        this.malformed = malformed;
        ui.drawing(drawingNote(fit.cell(), malformed));
    }

    /** What the status line's drawing slot says, empty when the lanes are drawn as they should be. Pure, for a test. */
    static String drawingNote(int cell, int malformed) {
        String coarse = cell > 1 ? "Lanes drawn in " + cell + " px cells: more events than the window can draw" : "";
        String bad = malformed == 1 ? "1 event not drawn: it ends before it begins"
                : malformed > 1 ? malformed + " events not drawn: they end before they begin" : "";
        return coarse.isEmpty() || bad.isEmpty() ? coarse + bad : coarse + ".  " + bad;
    }

    /**
     * Where the right edge is now and where each of the motion's handovers lands: the moments the strip's picture must
     * cover. One that is not a number is left out and logged, and if none is left, the timeline's edge stands for them.
     */
    private double[] rests(Motion edge, long now) {
        double[] rests = new double[1 + edge.handovers().size()];
        int kept = 0;
        for (int i = 0; i < rests.length; i++) {
            double at;
            if (i == 0) {
                at = edge.at(now);
            } else {
                Handover h = edge.handovers().get(i - 1);
                at = h.to().at(h.endNanos());
            }
            if (Double.isFinite(at)) {
                rests[kept++] = at;
            } else {
                LOG.error("a handover of the lanes' motion lands at {}; left out of the picture's reach", at);
            }
        }
        return kept == 0 ? new double[] {last.edgeNanos()} : Arrays.copyOf(rests, kept);
    }

    /** The most marks the strip's picture may hold before it is drawn coarser: well inside a window's vertex buffer. */
    static final int MARK_BUDGET = 12_000;

    /** The widest the strip may be, in pixels, whatever the lane's width: its columns are counted a pixel each. */
    static final int MAX_STRIP_PX = 1 << 18;

    /** The most lanes the strip may reach past a lane {@code laneW} wide, each side: {@link #SLIDE_LANES} at least. */
    static int mostReach(float laneW) {
        int byPixels = (int) ((MAX_STRIP_PX / Math.max(laneW, 1f) - 1) / 2);
        return Math.clamp(byPixels, SLIDE_LANES, MAX_REACH);
    }

    /**
     * How many lanes the strip must reach past the lane each side for a picture placing {@code edgeNanos} at the lane's
     * right edge to cover every right edge from {@code loNanos} to {@code hiNanos}, lanes spanning {@code spanNanos} and
     * as much as {@code widestNanos} while a zoom goes on, with one more for the present moving on. Pure, for a test.
     */
    static double lanesNeeded(double edgeNanos, double loNanos, double hiNanos, double spanNanos, double widestNanos) {
        double far = Math.max(edgeNanos - loNanos, hiNanos - edgeNanos);
        double lanes = (far + widestNanos) / spanNanos + 1;
        return Double.isFinite(lanes) ? lanes : SLIDE_LANES;
    }

    /** {@code needed} lanes, as a reach: {@link #SLIDE_LANES} at least and {@code most} at most. Pure, for a test. */
    static int reach(double needed, int most) {
        return (int) Math.clamp(Math.ceil(needed), SLIDE_LANES, Math.max(most, SLIDE_LANES));
    }

    /** As {@link #reach(double, int)}, at most {@link #MAX_REACH}. Pure, for a test. */
    static int reach(double edgeNanos, double loNanos, double hiNanos, double spanNanos, double widestNanos) {
        return reach(lanesNeeded(edgeNanos, loNanos, hiNanos, spanNanos, widestNanos), MAX_REACH);
    }

    /**
     * The strip's picture, as {@link #lanes(Timeline, float, float, float, Inks, double, double, int, double...)}
     * draws it, unless that holds more than {@link #MARK_BUDGET} marks, which would overflow the window's vertex
     * buffer and cost the window its title bar and status line for as long as it lasted: then drawn again in cells
     * twice as wide each time until it fits.
     *
     * @param picture what to draw
     * @param cell    the widest cell it was drawn in, in pixels: 1 unless it had to be drawn coarser
     * @param wanted  how many marks it would have held at a pixel a cell
     */
    record Fit(Picture picture, int cell, int wanted) {
    }

    /** See {@link Fit}. Pure, for a test. */
    static Fit fit(Timeline t, float w, float h, float pxPerNano, Inks inks, double fromNanos, double toNanos,
                   int slideLanes, double... restsNanos) {
        return fit(t, Traced.NONE, w, h, pxPerNano, inks, fromNanos, toNanos, slideLanes, restsNanos);
    }

    /** As above, with the path {@code traced} marked on the lanes. */
    static Fit fit(Timeline t, Traced traced, float w, float h, float pxPerNano, Inks inks, double fromNanos,
                   double toNanos, int slideLanes, double... restsNanos) {
        Picture p = lanes(t, traced, w, h, pxPerNano, inks, fromNanos, toNanos, slideLanes, null, restsNanos);
        int wanted = p.marks().size();
        int columns = (int) Math.ceil(w * (1 + 2 * slideLanes));
        int cell = 1;
        while (p.marks().size() > MARK_BUDGET && cell < columns) {
            cell *= 2;
            p = lanes(t, traced, w, h, pxPerNano, inks, fromNanos, toNanos, slideLanes, Cells.uniform(cell),
                    restsNanos);
        }
        return new Fit(p, cell, wanted);
    }

    /** How many of {@code t}'s events end before they begin, which no lane draws. Pure, for a test. */
    static int malformed(Timeline t) {
        int n = 0;
        for (Lane l : t.lanes()) {
            for (Mark m : l.marks()) {
                if (m.toNanos() < m.fromNanos()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Every lane's events between {@code fromNanos} and {@code toNanos} as marks on a strip reaching
     * {@link #SLIDE_LANES} lanes of width {@code w} either side of the lanes, with the timeline's edge moment where the
     * lanes' right edge is at rest, each lane a band of {@code h} over {@code Model.LANES}, a hairline between bands.
     * A stretch is a fill as long as it took, which a zoom stretches, and a one-pixel line at its end, which it does
     * not, so one far shorter than a pixel still shows at any zoom and none grows fat; both stand on the band's floor,
     * as tall as {@link #stature} says, so a long block and a storm of short ones look different even where the storm
     * packs into one solid run. A moment is the line alone, the band's whole reach. A pixel shows at most one mark,
     * {@link Columns}: the tallest end in it, or its moments in a strength by how many share it, and touching alike
     * stretches are one fill, so the picture is bounded by its width rather than by how many events it spans. The
     * event the cursor is on, if any, is drawn over them in the text's colour, its band's whole height, with a
     * two-pixel line at its end; and while stopped, the cursor itself, a one-pixel line in that colour across every
     * lane at the edge moment. Pure, for a test.
     */
    static Picture lanes(Timeline t, float w, float h, float pxPerNano, Inks inks, double fromNanos, double toNanos) {
        return lanes(t, w, h, pxPerNano, inks, fromNanos, toNanos, SLIDE_LANES);
    }

    /**
     * As {@link #lanes(Timeline, float, float, float, Inks, double, double)}, on a strip reaching {@code reach} lanes
     * either side, and {@code restsNanos} the right-edge moments a glide starts and lands at. A strip wider than
     * {@link Cells#FINE_PX} is drawn a pixel a column only about those, and coarser between, which a glide crosses
     * too fast to see.
     */
    static Picture lanes(Timeline t, float w, float h, float pxPerNano, Inks inks, double fromNanos, double toNanos,
                         int slideLanes, double... restsNanos) {
        return lanes(t, Traced.NONE, w, h, pxPerNano, inks, fromNanos, toNanos, slideLanes, null, restsNanos);
    }

    /**
     * As above, with the path {@code traced} marked, in {@code cells} if they are given, or else in cells fine about
     * {@code restsNanos}. Every event on the path is drawn over the lanes in the path's colour, its band's whole height,
     * a stretch as long as it took and a moment as a line, so a click's tick and draw stand out among their lanes'; the
     * event the cursor is on is drawn over it.
     */
    static Picture lanes(Timeline t, Traced traced, float w, float h, float pxPerNano, Inks inks, double fromNanos,
                         double toNanos, int slideLanes, Cells given, double... restsNanos) {
        Sketch s = new Sketch().tag("lanes");
        float edge = w * (1 + slideLanes);
        float band = h / Model.LANES.size();
        for (int i = 1; i < Model.LANES.size(); i++) {
            s.line(0f, band * i, edge + w * slideLanes, band * i, 1f, inks.line());
        }
        int columns = (int) Math.ceil(edge + w * slideLanes);
        float[] rests = new float[restsNanos.length];
        for (int i = 0; i < rests.length; i++) {
            rests[i] = edge + (float) ((restsNanos[i] - t.edgeNanos()) * pxPerNano);
        }
        Cells cells = given != null ? given : Cells.about(columns, w, rests);
        Columns ends = new Columns(columns);
        Columns moments = new Columns(columns);
        for (Lane l : t.lanes()) {
            float y = band * Model.LANES.indexOf(l.name());
            float floor = y + band * 0.8f;
            float reach = band * 0.6f;
            ends.clear();
            moments.clear();
            float runX0 = 0f;
            float runX1 = -1f;
            float runH = 0f;
            Color runC = null;
            for (Mark m : l.marks()) {
                if (m.toNanos() < fromNanos || m.fromNanos() > toNanos || m.toNanos() < m.fromNanos()) {
                    continue;   // out of range, or ending before it begins, which {@link #malformed} counts
                }
                Color c = inks.of(m.kind());
                float x0 = edge + (m.fromNanos() - t.edgeNanos()) * pxPerNano;
                float x1 = edge + (m.toNanos() - t.edgeNanos()) * pxPerNano;
                int column = (int) Math.ceil(x1) - 1;     // the pixel the end is in
                if (m.toNanos() == m.fromNanos()) {
                    moments.count(column, c);
                    continue;
                }
                float height = reach * stature(m.toNanos() - m.fromNanos());
                ends.tallest(column, Math.round(height), c);
                if (x1 < 0f || x0 > columns || x1 - x0 < cells.width(column)) {
                    continue;   // its end's cell is all a pixel, or a cell of a glide, can show of it
                }
                if (c.equals(runC) && Math.abs(height - runH) < 0.5f && x0 <= runX1 + 0.5f) {
                    runX1 = Math.max(runX1, x1);    // touching and alike: one fill, so a packed storm is one bar
                    continue;
                }
                if (runC != null) {
                    s.fill(runX0, floor - runH, runX1 - runX0, runH, runC);
                }
                runX0 = x0;
                runX1 = x1;
                runH = height;
                runC = c;
            }
            if (runC != null) {
                s.fill(runX0, floor - runH, runX1 - runX0, runH, runC);
            }
            ends.draw(s, floor, reach, false, cells);
            moments.draw(s, floor, reach, true, cells);
        }
        if (!traced.equals(Traced.NONE)) {
            for (Lane l : t.lanes()) {
                float y = band * Model.LANES.indexOf(l.name());
                for (Mark m : l.marks()) {
                    if (m.toNanos() < fromNanos || m.fromNanos() > toNanos || m.toNanos() < m.fromNanos()
                            || !traced.has(l.name(), m)) {
                        continue;
                    }
                    float x0 = edge + (m.fromNanos() - t.edgeNanos()) * pxPerNano;
                    float x1 = edge + (m.toNanos() - t.edgeNanos()) * pxPerNano;
                    if (x1 - x0 >= 1f) {
                        s.fill(x0, y, x1 - x0, band, inks.path());
                    } else {
                        s.line(x1 - 0.5f, y, x1 - 0.5f, y + band, 1f, inks.path());
                    }
                }
            }
        }
        t.on().ifPresent(e -> {
            Mark m = e.mark();
            float y = band * Model.LANES.indexOf(e.lane());
            float x0 = edge + (m.fromNanos() - t.edgeNanos()) * pxPerNano;
            float x1 = edge + (m.toNanos() - t.edgeNanos()) * pxPerNano;
            if (m.toNanos() > m.fromNanos()) {
                s.fill(x0, y, x1 - x0, band, inks.on());
            }
            s.line(x1 - 1f, y, x1 - 1f, y + band, 2f, inks.on());
        });
        if (!t.live()) {
            s.line(edge - 0.5f, 0f, edge - 0.5f, h, 1f, inks.on());
        }
        return s.picture();
    }

    /**
     * One lane's marks a pixel column at a time, so a lane draws at most a mark a column however many events share
     * one: each column keeps the tallest stretch ending in it, or how many moments are in it, and a run of alike
     * columns is drawn as one fill. A column alone is drawn as a line, as a mark alone always was. Built in the told
     * half, when the timeline changes; this is what keeps a wide picture, a catch-up's minute, inside the window's
     * vertex buffer.
     */
    static final class Columns {

        /** Moments sharing a column this many times over or more are drawn in their colour's full strength. */
        static final int DENSE = 32;
        /** How strong a moment alone in its column is drawn, of its colour's full strength. */
        static final float SPARSE = 0.45f;

        private final float[] height;
        private final int[] count;
        private final Color[] color;

        Columns(int columns) {
            height = new float[columns];
            count = new int[columns];
            color = new Color[columns];
        }

        void clear() {
            java.util.Arrays.fill(height, 0f);
            java.util.Arrays.fill(count, 0);
            java.util.Arrays.fill(color, null);
        }

        /** A stretch ends in {@code column}, standing {@code h} pixels. */
        void tallest(int column, int h, Color c) {
            if (column < 0 || column >= height.length) {
                return;
            }
            float stands = Math.max(1, h);
            if (stands > height[column]) {
                height[column] = stands;
                color[column] = c;
            }
        }

        /** A moment is in {@code column}. The first one in it gives the column its colour. */
        void count(int column, Color c) {
            if (column < 0 || column >= count.length) {
                return;
            }
            if (count[column]++ == 0) {
                color[column] = c;
            }
        }

        /**
         * Draw the columns standing on {@code floor}: as moments, the band's whole {@code reach} in a strength by how
         * many share the column, a step for each doubling; otherwise as the tallest stretch's end.
         */
        void draw(Sketch s, float floor, float reach, boolean moments, Cells cells) {
            int runFrom = 0;
            int runTo = 0;
            float runKey = 0f;
            Color runC = null;
            for (int x = 0; x < color.length; ) {
                int to = Math.min(cells.end(x), color.length);
                // The cell's columns as one: the tallest end and its colour, or every moment and the first's colour.
                Color c = null;
                float tallest = 0f;
                int n = 0;
                for (int i = x; i < to; i++) {
                    if (color[i] == null) {
                        continue;
                    }
                    if (moments ? c == null : height[i] > tallest) {
                        c = color[i];
                    }
                    tallest = Math.max(tallest, height[i]);
                    n += count[i];
                }
                float key = moments ? level(n) : tallest;
                if (c == null || !c.equals(runC) || key != runKey) {
                    drawRun(s, floor, reach, moments, runFrom, runTo, runKey, runC);
                    runC = c;
                    runFrom = x;
                    runKey = key;
                }
                runTo = to;
                x = to;
            }
            drawRun(s, floor, reach, moments, runFrom, runTo, runKey, runC);
        }

        /** A run of alike columns: a line if it is one pixel, else a fill. */
        private static void drawRun(Sketch s, float floor, float reach, boolean moments, int from, int to, float key,
                                    Color c) {
            if (c == null) {
                return;
            }
            float h = moments ? reach : key;
            Color ink = moments ? Color.withAlpha(c, c.a() * strengthAt((int) key)) : c;
            if (to - from == 1) {
                s.line(from + 0.5f, floor - h, from + 0.5f, floor, 1f, ink);
            } else {
                s.fill(from, floor - h, to - from, h, ink);
            }
        }

        /** How many doublings past one moment {@code n} is, up to {@link #DENSE}. */
        static int level(int n) {
            return Math.min(31 - Integer.numberOfLeadingZeros(n), 31 - Integer.numberOfLeadingZeros(DENSE));
        }

        /** How strong moments {@code level} doublings past one are drawn: {@link #SPARSE} alone, full at {@link #DENSE}. */
        static float strengthAt(int level) {
            float steps = 31 - Integer.numberOfLeadingZeros(DENSE);
            return SPARSE + (1f - SPARSE) * level / steps;
        }
    }

    /**
     * How wide a strip's cells are, a cell being what {@link Columns} draws as one: a pixel near where a glide starts
     * and lands, where the lanes are looked at, and coarser between, where a glide crosses lanes a frame and nothing
     * finer than a cell can be seen. A strip no wider than {@link #FINE_PX} is a pixel a cell throughout, so what the
     * lanes look like at rest never depends on how far the last glide went.
     */
    static final class Cells {

        /** A strip this wide or narrower is fine throughout: the least reach, with room. */
        static final int FINE_PX = 20_000;
        /** About how many cells the coarse stretches of a wider strip are cut into, all told. */
        static final int COARSE_CELLS = 4_000;

        static final Cells FINE = new Cells(1, new int[0]);

        /** Cells {@code px} wide throughout, which {@link #fit} falls back to when a picture holds too much. */
        static Cells uniform(int px) {
            return new Cells(Math.max(1, px), new int[0]);
        }

        private final int coarse;
        /** The fine windows, as pixel ranges {@code [from, to)} in order, two ints each. */
        private final int[] fine;

        private Cells(int coarse, int[] fine) {
            this.coarse = coarse;
            this.fine = fine;
        }

        /**
         * The cells of a strip {@code columns} pixels wide whose lanes are {@code w} wide, fine from two lanes before
         * each of {@code restsPx} (a right edge's place on the strip) to a lane after it.
         */
        static Cells about(int columns, float w, float[] restsPx) {
            if (columns <= FINE_PX || restsPx.length == 0) {
                return FINE;
            }
            float[] sorted = restsPx.clone();
            java.util.Arrays.sort(sorted);
            int[] windows = new int[sorted.length * 2];
            int pairs = 0;
            int fineTotal = 0;
            for (float rest : sorted) {
                int from = Math.clamp((int) Math.floor(rest - 2 * w), 0, columns);
                int to = Math.clamp((int) Math.ceil(rest + w), 0, columns);
                if (pairs > 0 && from <= windows[2 * pairs - 1]) {
                    fineTotal += Math.max(0, to - windows[2 * pairs - 1]);
                    windows[2 * pairs - 1] = Math.max(to, windows[2 * pairs - 1]);
                } else if (to > from) {
                    windows[2 * pairs] = from;
                    windows[2 * pairs + 1] = to;
                    fineTotal += to - from;
                    pairs++;
                }
            }
            int coarse = Math.max(1, (int) Math.ceil((columns - fineTotal) / (double) COARSE_CELLS));
            return new Cells(coarse, java.util.Arrays.copyOf(windows, 2 * pairs));
        }

        /** Where the cell starting at pixel {@code x} ends: the next pixel in a fine window, else a coarse step on. */
        int end(int x) {
            int next = (x / coarse + 1) * coarse;
            for (int i = 0; i < fine.length; i += 2) {
                if (x >= fine[i] && x < fine[i + 1]) {
                    return x + 1;
                }
                if (fine[i] > x && fine[i] < next) {
                    next = fine[i];
                }
            }
            return next;
        }

        /** How wide the cell holding pixel {@code x} is. */
        int width(int x) {
            for (int i = 0; i < fine.length; i += 2) {
                if (x >= fine[i] && x < fine[i + 1]) {
                    return 1;
                }
            }
            return coarse;
        }
    }

    /** A stretch this short or shorter stands {@link #STATURE_MIN} of its band's reach. */
    static final double STATURE_SHORT_NANOS = 100_000.0;
    /** A stretch this long or longer stands its band's whole reach. */
    static final double STATURE_LONG_NANOS = 1_000_000_000.0;
    /** The least a stretch stands, so the shortest still reads as a bar. */
    static final float STATURE_MIN = 1f / 6f;

    /**
     * How tall a stretch of {@code nanos} stands, as a share of its band's reach: by the log of how long it took, from
     * {@link #STATURE_SHORT_NANOS} to {@link #STATURE_LONG_NANOS}, so a storm of short passes is a low comb and a long
     * one a tall slab at any zoom, however tightly the storm is packed.
     */
    static float stature(long nanos) {
        double share = Math.log(nanos / STATURE_SHORT_NANOS) / Math.log(STATURE_LONG_NANOS / STATURE_SHORT_NANOS);
        return (float) Math.clamp(share, STATURE_MIN, 1.0);
    }

    // ------------------------------------------------------------------ the details

    /** The details panel's clock row, the one row that is not a field of the event. */
    static final String CLOCK = "Clock time";

    /** The details panel's row for the row the cursor is on, after a step by rows: not a field of an event either. */
    static final String ROW = "Row";

    /**
     * The details panel's labels, top to bottom: the clock, the row the cursor is on, then the event schema's fields
     * ({@code Model.FIELDS}), a row each, shown only while the cursor is on a row, or on an event that has that field.
     */
    static final List<String> DETAILS = java.util.stream.Stream.concat(java.util.stream.Stream.of(CLOCK, ROW),
            Model.FIELDS.stream()).toList();

    /** What the type row says when the cursor is on no event. */
    static final String NO_EVENT = "no event at the cursor";

    /** The local time of day, to the microsecond, as the application's own log has it. */
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS", Locale.ROOT);

    /** The bench's details of the cursor's moment, into the panel. */
    @Subscribe
    public void details(Details d) {
        ui.details(details(d, ZoneId.systemDefault()));
    }

    /** The path of the event the cursor is on, marked on the lanes, the graph and the flows table. */
    private Traced traced = Traced.NONE;

    /**
     * Mark {@code t}'s path in every panel: the lanes' events on it, the graph's arrows it crossed, the flows table's
     * topics it crossed. Drawn again only when the path changes, which is when the cursor's event does.
     */
    @Subscribe
    public void traced(Traced t) {
        if (t.equals(traced)) {
            return;
        }
        traced = t;
        ui.crossed(crossedTopics(t));
        ui.rows(rowCells(rowsAround), rowInks(rowsAround, traced), rowsAround.selected());
        ui.graph().picture(Diagram.draw(lastFlows, traced, graphW, graphH, diagramInks));
        show();
    }

    /** The topics {@code t} crossed, each once, in the order it first crossed them. Pure, for a test. */
    static List<String> crossedTopics(Traced t) {
        return t.crossed().stream().map(Crossed::topic).distinct().toList();
    }

    /**
     * What the details panel says, a value for each of {@link #DETAILS}, empty for a row to hide: the run's clock as
     * local time of day, as the application's log has it, then as UTC, as its probe's rows have it; and each field of
     * the event the cursor is on, as the rows filled it. Pure, for a test.
     */
    static List<String> details(Details d, ZoneId zone) {
        List<String> values = new java.util.ArrayList<>(java.util.Collections.nCopies(DETAILS.size(), ""));
        if (d.run().isEmpty() && d.atNanos() == 0L) {
            return values;
        }
        String clock = d.clockNanos() < 0 ? "not said by the run" : clock(d.clockNanos(), zone);
        values.set(0, clock + String.format(Locale.ROOT, "   %.3f s into the run", d.intoNanos() / 1e9));
        if (d.row().isPresent()) {
            Row r = d.row().get();
            values.set(DETAILS.indexOf(Model.TYPE), "row");
            values.set(DETAILS.indexOf(ROW), (r.seq() < 0 ? "added by the link" : "#" + r.seq()) + " · " + r.lane() + " · "
                    + r.kind() + (r.detail().isEmpty() ? "" : " · " + r.detail())
                    + (r.cause().isEmpty() ? "" : "   (" + r.cause() + ")"));
            values.set(DETAILS.indexOf(Model.THREAD), r.thread());
            return values;
        }
        if (d.on().isEmpty()) {
            values.set(DETAILS.indexOf(Model.TYPE), NO_EVENT);
            return values;
        }
        for (Field f : d.on().get().mark().fields()) {
            int row = DETAILS.indexOf(f.name());
            if (row > 0) {
                // A hop a line: the field keeps its arrows on one line, as a filter matches it.
                values.set(row, f.name().equals(Model.PATH) ? f.value().replace(" → ", "\n→ ") : f.value());
            }
        }
        return values;
    }

    /** {@code epochNanos} as local time of day in {@code zone}, then as UTC to the microsecond. */
    static String clock(long epochNanos, ZoneId zone) {
        Instant at = Instant.ofEpochSecond(Math.floorDiv(epochNanos, 1_000_000_000L),
                Math.floorMod(epochNanos, 1_000_000_000L));
        return LOCAL.format(at.atZone(zone)) + "   " + at.truncatedTo(ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------ the readouts

    /** The bench's readouts, as one line of numbers. */
    /** The run panel: what is inspected, from where, and the running applications to choose among. */
    @Subscribe
    public void runPanel(RunPanel p) {
        Run current = null;
        for (Run r : p.choices()) {
            if (r.name().equals(p.run())) {
                current = r;
            }
        }
        ui.runPanel(runLine(p), p.choices(), current, !p.run().isEmpty());
    }

    /** The run panel's line: the run, its process, how it was reached and where its logic runs. */
    static String runLine(RunPanel p) {
        if (p.run().isEmpty()) {
            return "No application inspected";
        }
        StringBuilder b = new StringBuilder(p.title().isBlank() ? p.run() : p.title());
        if (p.pid() > 0) {
            b.append(" · pid ").append(p.pid());
        }
        b.append(" · ").append(p.from());
        if (!p.logic().isEmpty()) {
            b.append(" · ").append(p.logic());
        }
        return b.toString();
    }

    @Subscribe
    public void readouts(Readouts r) {
        ui.readouts(readout(r));
    }

    // ------------------------------------------------------------------ the rows panel

    /** The rows panel's columns, in order. */
    static final List<String> ROW_COLUMNS = List.of("From cursor", "Thread", "Lane", "Kind", "Detail", "Cause");

    /** The rows around the cursor the bench last said, for a new path to colour again. */
    private RowsAround rowsAround = RowsAround.NONE;

    /** The bench's rows around the cursor, into the rows panel. */
    @Subscribe
    public void rowsAround(RowsAround r) {
        rowsAround = r;
        ui.rows(rowCells(r), rowInks(r, traced), r.selected());
    }

    /**
     * What the rows panel says, a row a place, a cell for each of {@link #ROW_COLUMNS}: how long before or after the
     * cursor's row it was written, its thread, lane and kind, what it said, and its cause. Pure, for a test.
     */
    static List<List<String>> rowCells(RowsAround r) {
        long at = r.selected() < 0 ? 0L : r.rows().get(r.selected()).atNanos();
        List<List<String>> cells = new ArrayList<>(r.rows().size());
        for (int i = 0; i < r.rows().size(); i++) {
            Row row = r.rows().get(i);
            String when = i == r.selected() ? "the cursor's"
                    : r.selected() < 0 ? "" : String.format(Locale.ROOT, "%+.3f ms", (row.atNanos() - at) / 1e6);
            cells.add(List.of(when, row.thread(), row.lane(), row.kind(), row.detail(), row.cause()));
        }
        return cells;
    }

    /** Each row's ink: the cursor's in the accent, those on {@code t}'s path in its colour. Pure, for a test. */
    static List<Role> rowInks(RowsAround r, Traced t) {
        List<Role> inks = new ArrayList<>(r.rows().size());
        for (int i = 0; i < r.rows().size(); i++) {
            inks.add(i == r.selected() ? Role.ACCENT : t.has(r.rows().get(i)) ? Ui.PATH : Role.INK);
        }
        return inks;
    }

    /** The threads panel's columns, in order. */
    static final List<String> THREAD_COLUMNS = List.of("Clock", "Turn", "Length", "Working", "Waiting", "Resting",
            "Unaccounted", "Recording", "Wakes", "Wake to run", "Note");

    @Subscribe
    public void ledger(LedgerAt l) {
        ui.threads(threadCells(l));
    }

    /**
     * What the threads panel says, a line a clock, a cell for each of {@link #THREAD_COLUMNS}: the turn and how long
     * it was, each state's time and its share of the turn, recording's, the wakes and how long the first took to run,
     * and what is not whole. A figure the rows cannot fill is blank. Pure, for a test.
     */
    static List<List<String>> threadCells(LedgerAt l) {
        List<List<String>> cells = new ArrayList<>(l.accounts().size());
        for (Account a : l.accounts()) {
            long length = a.toNanos() - a.fromNanos();
            cells.add(List.of(a.clock(), a.turn(), a.turn().isEmpty() ? "" : millis(length),
                    share(a.working(), length), share(a.waiting(), length), share(a.resting(), length),
                    share(a.unaccounted(), length), share(a.recording(), length),
                    a.wakes() < 0 ? "" : Integer.toString(a.wakes()), a.latency() < 0 ? "" : millis(a.latency()),
                    a.note()));
        }
        return cells;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }

    /** {@code nanos} and its share of {@code length}: blank when the rows cannot say it. */
    private static String share(long nanos, long length) {
        return nanos < 0 || length <= 0 ? ""
                : String.format(Locale.ROOT, "%.2f ms %3d%%", nanos / 1e6, Math.round(100.0 * nanos / length));
    }

    // ------------------------------------------------------------------ the replay panel

    /** The replay panel's columns, in order. */
    static final List<String> REPLAY_COLUMNS = List.of("Tick", "Send", "Topic", "Recorded", "Replayed",
            "Sent when run again");

    /** How many differences the replay panel has places for: as many as a run lists (the framework's 16). */
    static final int REPLAY_LISTED = 16;

    /** What running again last found, for a click on a difference to find its tick. */
    private ReplayReport replayReport = ReplayReport.NONE;

    /** What running again found, into the replay panel. */
    @Subscribe
    public void replayed(ReplayReport r) {
        replayReport = r;
        ui.replay(r.said(), replayCells(r));
    }

    /**
     * What the replay panel says, a difference a place, a cell for each of {@link #REPLAY_COLUMNS}: its tick, which of
     * the tick's sends, its topic, the two fingerprints, and what was sent when run again. Pure, for a test.
     */
    static List<List<String>> replayCells(ReplayReport r) {
        List<List<String>> cells = new ArrayList<>(r.differences().size());
        for (Difference d : r.differences()) {
            cells.add(List.of("#" + d.tick(), d.send() < 0 ? "" : Integer.toString(d.send()), d.topic(),
                    d.recorded(), d.replayed(), d.what()));
        }
        return cells;
    }

    /** The flows table's columns, in order. */
    static final List<String> FLOW_COLUMNS = List.of("Topic", "Kind", "From", "To", "Written", "Delivered",
            "Per second");

    /** A topic's cells before anything is known of it. */
    static final List<String> NO_FLOW = Collections.nCopies(FLOW_COLUMNS.size(), "");

    @Subscribe
    public void flows(Flows f) {
        List<String> topics = new ArrayList<>(f.flows().size());
        Map<String, List<String>> cells = new HashMap<>();
        for (Flow flow : f.flows()) {
            topics.add(flow.topic());
            cells.put(flow.topic(), flowCells(flow));
        }
        ui.flows(f.why(), topics, cells);
        lastFlows = f;
        ui.graph().picture(Diagram.draw(f, traced, graphW, graphH, diagramInks));
    }

    /** The graph panel's colours, and its size and what it last showed, for a resize to draw it again. */
    private final Diagram.Inks diagramInks;
    private float graphW;
    private float graphH;
    private Flows lastFlows = new Flows("", "", List.of(), List.of());

    /**
     * What a topic's row says, a cell for each of {@link #FLOW_COLUMNS}: its type's name, its kind, who sends it and
     * who hears it by their classes' names with what crossed each, what was written and delivered, and written a
     * second. Pure, for a test.
     */
    static List<String> flowCells(Flow f) {
        // Heard only by parts no row counts deliveries to (a job, a source): there is no number to say.
        boolean uncounted = !f.to().isEmpty() && f.to().stream().allMatch(e -> e.count() == End.UNCOUNTED);
        return List.of(Model.typeName(f.topic()), f.kind(), ends(f.from()), ends(f.to()),
                Long.toString(f.written()), uncounted ? "" : Long.toString(f.delivered()),
                f.perSecond() <= 0.0 ? "" : String.format(Locale.ROOT, "%.0f", f.perSecond()));
    }

    /** Ends as a list in words: each part by its class's name ({@code View#timeline}), then what crossed it. */
    private static String ends(List<End> ends) {
        List<String> said = new ArrayList<>(ends.size());
        for (End e : ends) {
            String part = e.part().equals(End.FRAMEWORK) || e.part().equals("outside a tick") ? e.part()
                    : Model.typeName(e.part());
            said.add(e.count() == End.UNCOUNTED ? part : part + " " + e.count());
        }
        return String.join(", ", said);
    }

    /** What the readouts line says. Pure, for a test. */
    static String readout(Readouts r) {
        if (r.run().isEmpty() && r.draws() == 0 && r.ticks() == 0) {
            return "";
        }
        return String.format(Locale.ROOT, "Draws %d/s, held %d for the ceiling and %d for the GPU, recording p50 %.2f"
                        + " ms, p95 %.2f ms   Ticks %d/s, p50 %.2f ms, p95 %.2f ms   Rows lost %d", r.draws(),
                r.heldForCeiling(), r.heldForGpu(), r.recordingP50Nanos() / 1e6, r.recordingP95Nanos() / 1e6, r.ticks(),
                r.tickP50Nanos() / 1e6, r.tickP95Nanos() / 1e6, r.rowsLost());
    }

    /**
     * The asked half: where the motions put the right edge and the zoom at the frame's present; the picture stretched
     * from the scale it was built at to the zoom's, about the edge moment; and the strip slid by how far the right
     * edge is from that moment. Due again while either moves.
     */
    @Draw
    static long draw(Strip s, Frame frame, Drawn out) {
        long t = frame.monoNanos();
        boolean still = frame.reducedMotion();
        double edge = still ? s.edge().last().at(t) : s.edge().at(t);
        double scale = s.laneW() / Math.exp(still ? s.zoom().last().at(t) : s.zoom().at(t));
        float stretch = s.pictureScale() > 0f ? (float) (scale / s.pictureScale()) : 1f;
        out.stretch(s.strip(), stretch, s.laneW() * (1 + s.reach()));
        out.offset(s.strip(), -s.reach() * s.laneW() + (float) ((s.edgeNanos() - edge) * scale), 0f);
        return s.edge().moving(t) || s.zoom().moving(t) ? frame.presentNanos() : Frame.NEVER;
    }
}
