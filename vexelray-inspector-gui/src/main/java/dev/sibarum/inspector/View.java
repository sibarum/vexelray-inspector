package dev.sibarum.inspector;

import dev.sibarum.inspector.Glide.Handover;
import dev.sibarum.inspector.Glide.Path;
import dev.sibarum.inspector.Keys.EventKey;
import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.Keys.StepKey;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Span;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.StepEvent;
import dev.sibarum.inspector.core.Messages.Timeline;
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

import java.util.ArrayList;
import java.util.List;
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
 * <p>Space pauses and follows again, the arrows step the cursor onto the next or previous event, and Shift with them
 * steps a second. Each chord is declared with the message it sends ({@link PauseKey}, {@link EventKey},
 * {@link StepKey}), and the view turns it into the bench's {@link Pause}, {@link StepEvent} or {@link Step} with the
 * moment the lane would come to rest at, which only the view knows. The handover starts at the key, though it is added
 * when the bench answers, a tick later: the step is still flat there in every derivative, so starting it a few
 * milliseconds back moves nothing that can be seen.
 *
 * <p><b>The event the cursor is on</b> is drawn over the others in the text's colour, the whole height of the lane
 * and at least two pixels wide, so that it can be told from its neighbours even where a pass is far narrower than a
 * pixel.
 */
@dev.vexelray.framework.api.View
final class View {

    /** How much time the lane spans, right edge to left: the bench's reach. */
    static final long SPAN_NANOS = 10_000_000_000L;

    /**
     * How many lanes wide the strip reaches past the lane on each side: room for the longest glide the kept minute
     * allows (six lanes of ten seconds), and one more for the present moving on during it. Ui sizes the strip by it.
     */
    static final int SLIDE_LANES = 7;

    /** How far Shift and an arrow step the cursor. */
    static final long STEP_NANOS = 1_000_000_000L;

    /**
     * The view's state, which its {@link #draw} reads.
     *
     * @param strip      the node the marks are drawn on, a handle
     * @param edgeNanos  the moment the picture puts at the lane's right edge, on the machine's clock
     * @param pxPerNano  the lane's width over {@link #SPAN_NANOS}
     * @param restPx     the offset that puts the picture's edge moment at the lane's right edge: the strip reaches
     *                   {@link #SLIDE_LANES} lanes left of the lane
     * @param from       the path the motion started on
     * @param handovers  each change since, oldest first: where the right edge is, as a function of the present
     */
    record Strip(Node strip, long edgeNanos, float pxPerNano, float restPx, Path from, List<Handover> handovers) {
        Strip {
            handovers = List.copyOf(handovers);
        }
    }

    /** The one state the asked half reads: replaced when the timeline, the motion or the lane changes. */
    Strip state;

    private final Gui gui;
    private final Ui ui;
    private final Color mark;
    private final Color on;
    private final Sender<Pause> pause;
    private final Sender<Step> step;
    private final Sender<StepEvent> stepEvent;

    /** What the bench last said, kept to draw again at a new size. */
    private Timeline last = new Timeline("", "", false, 0L, List.of(), Optional.empty());
    private float laneW;
    private float laneH;

    /** When the last key was pressed, so the motion it causes starts then; and, for a pause, the window it chose. */
    private long pressedAt = -1L;
    private long pressWindow = -1L;

    View(Gui gui, Ui ui, Sender<PauseKey> pauseKey, Sender<EventKey> eventKey, Sender<StepKey> stepKey,
         Sender<Pause> pause, Sender<StepEvent> stepEvent, Sender<Step> step) {
        this.gui = gui;
        this.ui = ui;
        this.pause = pause;
        this.step = step;
        this.stepEvent = stepEvent;
        this.mark = gui.theme().color(Role.ACCENT);
        this.on = gui.theme().color(Role.INK);
        this.state = new Strip(ui.strip(), 0L, 0f, 0f, Path.stopped(0.0), List.of());
        gui.hear(ui.lane(), NodeEvent.RESIZE, layout -> {
            laneW = layout.rect().w();
            laneH = layout.rect().h();
            show();
        });
        gui.shortcut(Shortcut.of(Key.SPACE), pauseKey, new PauseKey());
        gui.shortcut(Shortcut.of(Key.LEFT), eventKey, new EventKey(-1));
        gui.shortcut(Shortcut.of(Key.RIGHT), eventKey, new EventKey(1));
        gui.shortcut(Shortcut.of(Key.LEFT, Modifier.SHIFT), stepKey, new StepKey(-STEP_NANOS));
        gui.shortcut(Shortcut.of(Key.RIGHT, Modifier.SHIFT), stepKey, new StepKey(STEP_NANOS));
    }

    // ------------------------------------------------------------------ keys

    /** Space: ask the bench to stop where the lane would come to rest from now, or, stopped, to follow again. */
    @Subscribe
    public void pauseKey(PauseKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = Glide.window(0.0);
        pause.send(new Pause((long) resting(now, pressWindow)));
    }

    /** Shift and an arrow: ask the bench to step the cursor by time, from where the lane would rest if moving. */
    @Subscribe
    public void stepKey(StepKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = -1L;
        step.send(new Step(key.byNanos(), (long) resting(now, Glide.window(0.0))));
    }

    /** An arrow: ask the bench to step the cursor onto the next or previous event. */
    @Subscribe
    public void eventKey(EventKey key) {
        long now = System.nanoTime();
        pressedAt = now;
        pressWindow = -1L;
        stepEvent.send(new StepEvent(key.by(), (long) resting(now, Glide.window(0.0))));
    }

    private double resting(long now, long window) {
        return Glide.rest(state.from(), state.handovers(), now, window);
    }

    // ------------------------------------------------------------------ the bench's timeline

    /** The bench's timeline: say what it says, move to where it says, and draw it. */
    @Subscribe
    public void timeline(Timeline t) {
        long now = System.nanoTime();
        // Once a handover has landed, it and all before it are its path: from then on the motion is that path exactly.
        Path from = state.from();
        List<Handover> handovers = state.handovers();
        for (int i = handovers.size() - 1; i >= 0; i--) {
            if (handovers.get(i).endNanos() <= now) {
                from = handovers.get(i).to();
                handovers = handovers.subList(i + 1, handovers.size());
                break;
            }
        }
        boolean fresh = last.passes().isEmpty() || (!t.run().isEmpty() && !t.run().equals(last.run()));
        Path to = t.live() ? Path.following(now) : Path.stopped(stopAt(t, from, handovers, now));
        if (fresh || gui.reducedMotion()) {
            from = to;
            handovers = List.of();
        } else if (!same(Glide.last(from, handovers), to)) {
            long start = pressedAt >= 0 ? pressedAt : now;
            double distancePx = (to.at(now) - Glide.at(from, handovers, now)) * scale();
            // A pause stops where the key reckoned the lane would rest, over the window it reckoned with.
            long window = pressWindow >= 0 && to.rate() == 0.0 ? pressWindow : Glide.window(distancePx);
            List<Handover> next = new ArrayList<>(handovers);
            next.add(Glide.handover(from, handovers, start, window, to));
            handovers = next;
        }
        pressedAt = -1L;
        pressWindow = -1L;
        last = t;
        state = new Strip(ui.strip(), t.edgeNanos(), scale(), -SLIDE_LANES * laneW, from, handovers);
        ui.status(t.status());
        show();
    }

    /**
     * Where to stop. Where the bench put the cursor, if a key asked; if the run went away while followed, wherever the
     * lane comes to rest from now, so that it coasts to a stop rather than going back to the last row.
     */
    private double stopAt(Timeline t, Path from, List<Handover> handovers, long now) {
        if (pressedAt < 0 && Glide.last(from, handovers).rate() != 0.0) {
            return Glide.rest(from, handovers, now, Glide.window(0.0));
        }
        return t.edgeNanos();
    }

    private static boolean same(Path a, Path b) {
        return a.rate() == b.rate() && (a.rate() != 0.0 || a.value() == b.value());
    }

    private float scale() {
        return laneW > 0f ? laneW / SPAN_NANOS : 0f;
    }

    /** Build the strip's picture for the lane's size, over every moment the motion will pass through. */
    private void show() {
        if (laneW <= 0f) {
            return;     // not laid out yet: the lane's size arrives and draws it
        }
        float scale = scale();
        long now = System.nanoTime();
        double lo = Glide.at(state.from(), state.handovers(), now);
        double hi = lo;
        for (Handover h : state.handovers()) {
            double at = h.to().at(h.endNanos());
            lo = Math.min(lo, at);
            hi = Math.max(hi, at);
        }
        if (Glide.last(state.from(), state.handovers()).rate() != 0.0) {
            hi = Double.MAX_VALUE;
        }
        ui.strip().picture(passes(last, laneW, laneH, scale, mark, on, lo - SPAN_NANOS, hi));
        state = new Strip(ui.strip(), last.edgeNanos(), scale, -SLIDE_LANES * laneW, state.from(), state.handovers());
    }

    /**
     * The passes between {@code fromNanos} and {@code toNanos} as marks on a strip reaching {@link #SLIDE_LANES} lanes
     * of width {@code w} either side of the lane, with the timeline's edge moment where the lane's right edge is at
     * rest; each at least a pixel wide so that a pass far shorter than a pixel's worth of time still shows. The pass
     * the cursor is on, if any, is drawn over them in {@code onColour}, the lane's whole height and at least two pixels
     * wide, ending where it ends. Pure, for a test.
     */
    static Picture passes(Timeline t, float w, float h, float pxPerNano, Color colour, Color onColour,
                          double fromNanos, double toNanos) {
        Sketch s = new Sketch().tag("passes");
        float edge = w * (1 + SLIDE_LANES);
        float top = h * 0.2f;
        float height = h * 0.6f;
        for (Span p : t.passes()) {
            if (p.toNanos() < fromNanos || p.fromNanos() > toNanos) {
                continue;
            }
            float x0 = edge + (p.fromNanos() - t.edgeNanos()) * pxPerNano;
            float x1 = edge + (p.toNanos() - t.edgeNanos()) * pxPerNano;
            s.fill(x0, top, Math.max(1f, x1 - x0), height, colour);
        }
        t.on().ifPresent(p -> {
            float x0 = edge + (p.fromNanos() - t.edgeNanos()) * pxPerNano;
            float x1 = edge + (p.toNanos() - t.edgeNanos()) * pxPerNano;
            float width = Math.max(2f, x1 - x0);
            s.fill(x1 - width, 0f, width, h, onColour);
        });
        return s.picture();
    }

    /**
     * The asked half: where the motion puts the right edge at the frame's present, and the strip slid by how far that
     * is from the moment its picture puts there. Due again while anything moves.
     */
    @Draw
    static long draw(Strip s, Frame frame, Drawn out) {
        long t = frame.monoNanos();
        double edge = frame.reducedMotion() ? Glide.last(s.from(), s.handovers()).at(t)
                : Glide.at(s.from(), s.handovers(), t);
        out.offset(s.strip(), s.restPx() + (float) ((s.edgeNanos() - edge) * s.pxPerNano()), 0f);
        return Glide.moving(s.from(), s.handovers(), t) ? frame.presentNanos() : Frame.NEVER;
    }
}
