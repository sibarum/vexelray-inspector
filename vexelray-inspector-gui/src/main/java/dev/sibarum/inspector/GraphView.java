package dev.sibarum.inspector;

import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.core.Messages.Paused;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.vexelray.framework.api.Draw;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.draw.Picture;

import java.util.List;

/**
 * The graph window's view: the same graph as the main window's graph panel, from the same {@link Flows} and the same
 * path of the event the cursor is on, so the two never disagree; and here it can be looked around. The wheel or a
 * pinch zooms about the pointer, a drag moves it, a double-click fits it again ({@link GraphLens}).
 *
 * <p>A {@code @View} of its own, keeping to its window's widgets, as Mainframe's Workspaces window's is. It decides
 * nothing: where the graph is looked at from is the person's, and kept here, as the lanes' zoom is in {@link
 * dev.sibarum.inspector.View}. Every gesture is a redraw in the told half; what moves on its own is the dots, a message
 * each from the timeline's Messages lane, as in the panel ({@link Dots}), asked by this window's tree
 * ({@code @View(window)}: the main one would ignore them).
 */
@View(window = GraphWindow.KEY)
final class GraphView {

    /**
     * How far the wheel turns for one notch, px: the wheel arrives as a distance, not as notches (Pix's FN-6, the
     * same number).
     */
    static final float NOTCH_PX = 48f;

    private final GraphWindow window;
    private final Sender<PauseKey> pauseKey;
    private final Diagram.Inks inks;
    /** The dot nodes on the stage, and the arrows they run along at the lens's zoom, before it moves them. */
    private final Dots.Pool pool;
    private Diagram.Tracks dotTracks = Diagram.Tracks.NONE;

    /** The dots, a message each on its way, where the lens has them: the asked half's state. */
    Dots state = Dots.NONE;

    private Timeline timeline = new Timeline("", "", true, 0L, List.of(), java.util.Optional.empty(), "",
            java.util.Optional.empty());
    private Flows flows = new Flows("", "", List.of(), List.of());
    private Traced traced = Traced.NONE;
    private GraphLens lens = GraphLens.FITTED;
    private float stageW;
    private float stageH;
    /** Where the pointer is over the stage, from its top left: what the wheel zooms about. */
    private float pointerX;
    private float pointerY;
    /** The graph laid out at the lens's zoom, before it is moved: drawn again when the zoom or what it shows changes. */
    private Picture laid = Picture.EMPTY;
    private Picture shown = Picture.EMPTY;
    /** Whether a pinch is under way: Windows may send the same gesture as a Ctrl and wheel too, which zooms once. */
    private boolean pinching;

    GraphView(GraphWindow window, Sender<PauseKey> pauseKey) {
        this.window = window;
        this.pauseKey = pauseKey;
        this.inks = Diagram.Inks.of(window.gui());
        this.pool = new Dots.Pool(window.gui(), window.stage(), inks.name());
        var gui = window.gui();
        gui.hear(window.stage(), NodeEvent.RESIZE, layout -> {
            stageW = layout.rect().w();
            stageH = layout.rect().h();
            look(lens.held(stageW, stageH), true);
        });
        gui.hear(window.stage(), NodeEvent.POINTER, p -> {
            pointerX = p.localX();
            pointerY = p.localY();
        });
        gui.hear(window.stage(), NodeEvent.WHEEL, w -> {
            if (!pinching) {
                look(lens.wheel(-w.dy() / NOTCH_PX, pointerX, pointerY, stageW, stageH), true);
            }
        });
        gui.hear(window.stage(), NodeEvent.PINCH, p -> {
            pinching = p.phase() != dev.vexelray.gui.core.input.PinchEvent.Phase.END;
            look(lens.zoomed(p.scale(), pointerX, pointerY, stageW, stageH), true);
        });
        gui.hear(window.stage(), NodeEvent.DRAG, d -> {
            if (d.phase() == DragEvent.Phase.MOVE) {
                look(lens.moved(d.dx(), d.dy(), stageW, stageH), false);
            }
        });
        gui.hear(window.stage(), NodeEvent.DOUBLE_CLICK, c -> look(GraphLens.FITTED, true));
    }

    @Subscribe
    public void flows(Flows f) {
        flows = f;
        look(lens, true);
    }

    @Subscribe
    public void traced(Traced t) {
        traced = t;
        look(lens, true);
    }

    /** The lanes, whose Messages lane the dots are, and whether they follow the present or stand at the cursor. */
    @Subscribe
    public void timeline(Timeline t) {
        timeline = t;
        layDots();
    }

    /** Where the graph is looked at from now. */
    GraphLens lens() {
        return lens;
    }

    /**
     * Look at the graph through {@code next}: laid out again when {@code relay} says what is drawn or its zoom may have
     * changed, and moved to where the lens has it.
     */
    private void look(GraphLens next, boolean relay) {
        if (relay || next.zoom() != lens.zoom()) {
            laid = Diagram.draw(flows, traced, stageW * next.zoom(), stageH * next.zoom(), inks);
            dotTracks = Diagram.tracks(flows, stageW * next.zoom(), stageH * next.zoom());
        }
        lens = next;
        shown = laid.shifted(next.x(), next.y());
        window.stage().picture(shown);
        layDots();
    }

    /**
     * The dots laid out again, a message each, from the timeline last told ({@link Dots.Pool#dots}), at the cursor the
     * flows were last counted to, on the arrows at the lens's zoom moved where it has them.
     */
    private void layDots() {
        state = pool.dots(dotTracks, timeline, flows.atNanos(), lens.x(), lens.y());
    }

    /**
     * The asked half: each dot where its message is at the frame's present, the lanes' right edge while following, or
     * at the cursor ({@link Dots#place}). Due again while they move.
     */
    @Draw
    static long draw(Dots s, Frame frame, Drawn out) {
        Dots.place(s, s.moment(frame.monoNanos()), out);
        return s.moving() ? frame.presentNanos() : Frame.NEVER;
    }

    /** What the stage was last given to show. */
    Picture shown() {
        return shown;
    }

    /** Whether the recorder is paused: this window's pause button shows what its click would do. */
    @Subscribe
    public void paused(Paused p) {
        window.pause().show(p.paused());
    }

    /** This window's pause button: as Space, which the main view turns into a pause where its lanes come to rest. */
    @Subscribe
    public void pressed(Button.Pressed p) {
        if (p.button().equals(window.pause().address())) {
            pauseKey.send(new PauseKey());
        }
    }
}
