package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Span;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.vexelray.framework.api.Draw;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.canvas.Color;

import java.util.List;

/**
 * The view: what the bench decided, shown. A {@code @View}, so it runs on the GUI thread, in each frame's widget tick
 * (threading.md, T7.4), and it decides nothing: every decision is the {@code Bench}'s, in {@code -core}.
 *
 * <h2>Told builds, asked chooses</h2>
 *
 * <p>The timeline is the frame contract's first application witness (vexelray-gui's
 * {@code docs/plans/frame-contract.md}; vexelray-inspector's {@code docs/design.md} 9.1). Its marks arrive a batch at
 * a time, and its present moves on continuously, so the two are drawn by the two halves:
 *
 * <ul>
 *   <li><b>Told</b> ({@link #timeline}, and a new size of the lane): the strip's picture is built from the passes, with
 *       the newest row's moment at the lane's right edge, and the view's one state, a {@link Strip}, is replaced with
 *       that moment and the scale. Allocating is fine here: it happens when something changed.</li>
 *   <li><b>Asked</b> ({@link #draw}, every frame while the run is live): the strip is slid left by how far the present
 *       has moved on since that moment, so the newest row moves away from the edge at the rate time passes and the
 *       timeline follows the present between batches. It reads the state and the frame and allocates nothing, which
 *       the processor checks.</li>
 * </ul>
 *
 * <p><b>On the machine's clock.</b> The rows' moments are on {@code System.nanoTime()}'s clock (the link source set
 * them there), and so is {@link Frame#monoNanos}, so the strip is placed by subtracting the one from the other. The
 * frame's {@code presentNanos} is the tree's logical clock, which counts from its own zero: it is used only to say
 * when the next frame is due.
 *
 * <p>When no run is live nothing is due, so a paused or detached timeline draws nothing.
 */
@dev.vexelray.framework.api.View
final class View {

    /** How much time the lane spans, right edge to left: the bench's reach. */
    static final long SPAN_NANOS = 10_000_000_000L;

    /**
     * The view's state, which its {@link #draw} reads: the strip, where its picture puts the present, and the scale.
     *
     * @param strip     the node the marks are drawn on, a handle
     * @param edgeNanos the moment the picture puts at the lane's right edge, on the machine's clock
     * @param pxPerNano the lane's width over {@link #SPAN_NANOS}
     * @param live      whether rows are arriving, so the strip follows the present
     */
    record Strip(Node strip, long edgeNanos, float pxPerNano, boolean live) {
    }

    /** The one state the asked half reads: replaced when the timeline or the lane changes, never changed in place. */
    Strip state;

    private final Gui gui;
    private final Ui ui;
    private final Color mark;

    /** What the bench last said, kept to draw again at a new size. */
    private Timeline last = new Timeline("", "", false, 0L, List.of());
    private float laneW;
    private float laneH;

    View(Gui gui, Ui ui) {
        this.gui = gui;
        this.ui = ui;
        this.mark = gui.theme().color(Role.ACCENT);
        this.state = new Strip(ui.strip(), 0L, 0f, false);
        gui.hear(ui.lane(), NodeEvent.RESIZE, layout -> {
            laneW = layout.rect().w();
            laneH = layout.rect().h();
            show();
        });
    }

    /** The bench's timeline: say what it says, and draw it. */
    @Subscribe
    public void timeline(Timeline t) {
        last = t;
        ui.status(t.status());
        show();
    }

    /** Build the strip's picture for the lane's size, and the state the asked half slides it by. */
    private void show() {
        if (laneW <= 0f) {
            return;     // not laid out yet: the lane's size arrives and draws it
        }
        float scale = laneW / SPAN_NANOS;
        ui.strip().picture(passes(last, laneW, laneH, scale, mark));
        state = new Strip(ui.strip(), last.newestNanos(), scale, last.live());
    }

    /**
     * The passes as marks, the newest moment at the right edge, each at least a pixel wide so that a pass far shorter
     * than a pixel's worth of time still shows. Pure, for a test.
     */
    static Picture passes(Timeline t, float w, float h, float pxPerNano, Color colour) {
        Sketch s = new Sketch().tag("passes");
        float top = h * 0.2f;
        float height = h * 0.6f;
        for (Span p : t.passes()) {
            float x0 = w + (p.fromNanos() - t.newestNanos()) * pxPerNano;
            float x1 = w + (p.toNanos() - t.newestNanos()) * pxPerNano;
            if (x1 < 0f) {
                continue;
            }
            s.fill(x0, top, Math.max(1f, x1 - x0), height, colour);
        }
        return s.picture();
    }

    /**
     * The asked half: slide the strip left by how far the present has moved on since the moment at its right edge, and
     * want the next frame while the run is live. At rest the strip goes back to where layout put it.
     */
    @Draw
    static long draw(Strip s, Frame frame, Drawn out) {
        if (!s.live()) {
            out.offset(s.strip(), 0f, 0f);
            return Frame.NEVER;
        }
        out.offset(s.strip(), -(frame.monoNanos() - s.edgeNanos()) * s.pxPerNano(), 0f);
        return frame.presentNanos();
    }
}
