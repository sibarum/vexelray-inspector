package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Handover;
import dev.sibarum.inspector.Glide.Motion;
import dev.sibarum.inspector.Glide.Path;
import dev.sibarum.inspector.Keys.EventKey;
import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.Keys.StepKey;
import dev.sibarum.inspector.Keys.ZoomKey;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.GoTo;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Seek;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.StepEvent;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Model;
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
import dev.vexelray.canvas.Color;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;
import java.util.Locale;
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
 * <p>Space pauses and follows again, the arrows step the cursor onto the next or previous event of the focused lane,
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

    /** How much time the lane spans at first, right edge to left: the bench's reach. */
    static final long SPAN_NANOS = 10_000_000_000L;

    /** The least and most the lane can span: passes milliseconds apart a lane apart, and all the tape keeps. */
    static final long MIN_SPAN_NANOS = 10_000_000L;
    static final long MAX_SPAN_NANOS = 60_000_000_000L;

    /** How long a zoom by one press takes: as long as a move of this many pixels. */
    static final double ZOOM_AS_PX = 120.0;

    /**
     * How many lanes wide the strip reaches past the lane on each side: room for the longest glide the kept minute
     * allows at the first zoom (six lanes of ten seconds), and one more for the present moving on during it. Ui sizes
     * the strip by it.
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
     * @param laneW        the lane's width; the strip reaches {@link #SLIDE_LANES} of it past the lane each side
     * @param edge         where the lane's right edge is, a moment, as a function of the present
     * @param zoom         how much time the lane spans, as its natural logarithm, so that each doubling takes as long
     */
    record Strip(Node strip, long edgeNanos, float pictureScale, float laneW, Motion edge, Motion zoom) {
    }

    /**
     * The colours the lanes are drawn in: a stretch or a moment, a draw held for the ceiling, one held for the GPU,
     * injected input, the event the cursor is on, and the hairlines between the lanes.
     */
    record Inks(Color mark, Color ceiling, Color gpu, Color injected, Color on, Color line) {

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
    private final Sender<Step> step;
    private final Sender<StepEvent> stepEvent;
    private final Sender<GoTo> goTo;
    private final Sender<Seek> seek;

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

    /** When the last key was pressed, so the motion it causes starts then; and, for a pause, the window it chose. */
    private long pressedAt = -1L;
    private long pressWindow = -1L;

    View(Gui gui, Ui ui, Sender<PauseKey> pauseKey, Sender<EventKey> eventKey, Sender<StepKey> stepKey,
         Sender<ZoomKey> zoomKey, Sender<Pause> pause, Sender<StepEvent> stepEvent, Sender<Step> step,
         Sender<GoTo> goTo, Sender<Seek> seek) {
        this.gui = gui;
        this.ui = ui;
        this.pause = pause;
        this.step = step;
        this.stepEvent = stepEvent;
        this.goTo = goTo;
        this.seek = seek;
        this.inks = new Inks(gui.theme().color(Role.ACCENT), gui.theme().color(Role.FAINT),
                gui.theme().color(Role.CAUTION), gui.theme().color(Role.DIM), gui.theme().color(Role.INK),
                gui.theme().color(Role.LINE));
        this.state = new Strip(ui.strip(), 0L, 0f, 0f, Motion.of(Path.stopped(0.0)),
                Motion.of(Path.stopped(Math.log(span))));
        gui.hear(ui.lane(), NodeEvent.RESIZE, layout -> {
            laneX = layout.rect().x();
            laneY = layout.rect().y();
            laneW = layout.rect().w();
            laneH = layout.rect().h();
            show();
        });
        gui.hear(ui.lane(), NodeEvent.CLICK, click -> clicked(click.x(), click.y()));
        gui.shortcut(Shortcut.of(Key.SPACE), pauseKey, new PauseKey());
        gui.shortcut(Shortcut.of(Key.LEFT), eventKey, new EventKey(-1));
        gui.shortcut(Shortcut.of(Key.RIGHT), eventKey, new EventKey(1));
        gui.shortcut(Shortcut.of(Key.LEFT, Modifier.SHIFT), stepKey, new StepKey(-1));
        gui.shortcut(Shortcut.of(Key.RIGHT, Modifier.SHIFT), stepKey, new StepKey(1));
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
        state = new Strip(state.strip(), state.edgeNanos(), state.pictureScale(), state.laneW(), edge, zoom);
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
        ui.focus(focus);
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
        state = new Strip(state.strip(), state.edgeNanos(), state.pictureScale(), state.laneW(), edge,
                state.zoom().settled(now));
        ui.status(t.status());
        show();
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
        double lo = edge.at(now);
        double hi = lo;
        for (Handover h : edge.handovers()) {
            double at = h.to().at(h.endNanos());
            lo = Math.min(lo, at);
            hi = Math.max(hi, at);
        }
        if (edge.last().rate() != 0.0) {
            hi = Double.MAX_VALUE;
        }
        double widest = Math.max(span, Math.exp(state.zoom().at(now)));
        ui.strip().picture(lanes(last, laneW, laneH, scale, inks, lo - widest, hi));
        state = new Strip(ui.strip(), last.edgeNanos(), scale, laneW, edge, state.zoom());
    }

    /**
     * Every lane's events between {@code fromNanos} and {@code toNanos} as marks on a strip reaching
     * {@link #SLIDE_LANES} lanes of width {@code w} either side of the lanes, with the timeline's edge moment where the
     * lanes' right edge is at rest, each lane a band of {@code h} over {@code Model.LANES}, a hairline between bands.
     * A stretch is a fill as long as it took, which a zoom stretches, and a one-pixel line at its end, which it does
     * not, so one far shorter than a pixel still shows at any zoom and none grows fat; both stand on the band's floor,
     * as tall as {@link #stature} says, so a long block and a storm of short ones look different even where the storm
     * packs into one solid run. A moment is the line alone, the band's whole reach. The
     * event the cursor is on, if any, is drawn over them in the text's colour, its band's whole height, with a
     * two-pixel line at its end; and while stopped, the cursor itself, a one-pixel line in that colour across every
     * lane at the edge moment. Pure, for a test.
     */
    static Picture lanes(Timeline t, float w, float h, float pxPerNano, Inks inks, double fromNanos, double toNanos) {
        Sketch s = new Sketch().tag("lanes");
        float edge = w * (1 + SLIDE_LANES);
        float band = h / Model.LANES.size();
        for (int i = 1; i < Model.LANES.size(); i++) {
            s.line(0f, band * i, edge + w * SLIDE_LANES, band * i, 1f, inks.line());
        }
        for (Lane l : t.lanes()) {
            float y = band * Model.LANES.indexOf(l.name());
            float floor = y + band * 0.8f;
            float reach = band * 0.6f;
            for (Mark m : l.marks()) {
                if (m.toNanos() < fromNanos || m.fromNanos() > toNanos) {
                    continue;
                }
                Color c = inks.of(m.kind());
                float x0 = edge + (m.fromNanos() - t.edgeNanos()) * pxPerNano;
                float x1 = edge + (m.toNanos() - t.edgeNanos()) * pxPerNano;
                boolean stretch = m.toNanos() > m.fromNanos();
                float height = stretch ? reach * stature(m.toNanos() - m.fromNanos()) : reach;
                if (stretch) {
                    s.fill(x0, floor - height, x1 - x0, height, c);
                }
                s.line(x1 - 0.5f, floor - height, x1 - 0.5f, floor, 1f, c);
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

    // ------------------------------------------------------------------ the readouts

    /** The bench's readouts, as one line of numbers. */
    @Subscribe
    public void readouts(Readouts r) {
        ui.readouts(readout(r));
    }

    /** What the readouts line says. Pure, for a test. */
    static String readout(Readouts r) {
        if (r.run().isEmpty() && r.draws() == 0 && r.ticks() == 0) {
            return "";
        }
        return String.format(Locale.ROOT, "Draws %d/s, held %d for the ceiling and %d for the GPU   Ticks %d/s, "
                        + "p50 %.2f ms, p95 %.2f ms   Rows lost %d", r.draws(), r.heldForCeiling(), r.heldForGpu(),
                r.ticks(), r.tickP50Nanos() / 1e6, r.tickP95Nanos() / 1e6, r.rowsLost());
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
        out.stretch(s.strip(), stretch, s.laneW() * (1 + SLIDE_LANES));
        out.offset(s.strip(), -SLIDE_LANES * s.laneW() + (float) ((s.edgeNanos() - edge) * scale), 0f);
        return s.edge().moving(t) || s.zoom().moving(t) ? frame.presentNanos() : Frame.NEVER;
    }
}
