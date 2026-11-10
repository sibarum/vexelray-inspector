package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Flows;
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
 * dev.sibarum.inspector.View}. Every gesture is a redraw in the told half; what moves on its own is the dots along the
 * busy arrows, as in the panel, asked by this window's tree ({@code @View(window)}: the main one would ignore them).
 */
@View(window = GraphWindow.KEY)
final class GraphView {

    /**
     * How far the wheel turns for one notch, px: the wheel arrives as a distance, not as notches (Pix's FN-6, the
     * same number).
     */
    static final float NOTCH_PX = 48f;

    private final GraphWindow window;
    private final Diagram.Inks inks;
    /** The dot nodes on the stage, and the tracks and lens offset they were last laid out at. */
    private final Dots.Pool pool;
    private List<Diagram.Track> dotTracks = List.of();
    private float dotX = Float.NaN;
    private float dotY = Float.NaN;

    /** The dots running along the lit arrows, where the lens has them: the asked half's state. */
    Dots state = Dots.NONE;

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

    GraphView(GraphWindow window) {
        this.window = window;
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
        }
        lens = next;
        shown = laid.shifted(next.x(), next.y());
        window.stage().picture(shown);
        layDots();
    }

    /**
     * The dots laid out again on the arrows at the lens's zoom and moved where it has them: kept as they are when that
     * changes no dot, so a rate that stays in its step leaves them running where they were.
     */
    private void layDots() {
        List<Diagram.Track> tracks = Diagram.tracks(flows, stageW * lens.zoom(), stageH * lens.zoom());
        if (tracks.equals(dotTracks) && lens.x() == dotX && lens.y() == dotY) {
            return;
        }
        dotTracks = tracks;
        dotX = lens.x();
        dotY = lens.y();
        state = pool.dots(state, tracks, lens.x(), lens.y());
    }

    /** The asked half: the dots where they run to at the frame's present ({@link Dots#place}). Due again while any move. */
    @Draw
    static long draw(Dots s, Frame frame, Drawn out) {
        boolean still = frame.reducedMotion();
        Dots.place(s, frame.monoNanos(), still, out);
        return !still && s.moving() ? frame.presentNanos() : Frame.NEVER;
    }

    /** What the stage was last given to show. */
    Picture shown() {
        return shown;
    }
}
