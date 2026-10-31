package dev.sibarum.inspector;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar, the timeline, and a status line under both.
 *
 * <h2>The timeline</h2>
 *
 * <p>One lane per clock, each a name beside a strip of marks, with the present at the right edge (vexelray-inspector's
 * {@code docs/design.md} section 3). R1's first slice has one lane, the main loop's passes. A lane is a box that clips,
 * holding one {@link #strip} the size of the lane, whose picture the view draws: the view slides the strip left as the
 * present moves on, which is the frame contract's asked half, and the lane's clip is what keeps the slid strip inside
 * it (a container's clip is anchored, so a child slides within it).
 *
 * <h2>It holds no state</h2>
 *
 * <p>Every label here says what the bench last told the view, and the view tells it ({@link #status}). Nothing is
 * remembered here to be shown later.
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless. The title bar is the framework's; this application places it and supplies every colour in it
 * through {@link Look}.
 */
final class Ui {

    /** The column the lanes' names stand in. Type-relative, so it grows with the zoom like the names. */
    private static final Length NAMES = Length.rem(7);

    /** A lane's height. */
    private static final Length LANE = Length.rem(2.5f);

    private final StatusBar status;
    private final Node lane;
    private final Node strip;

    Ui(Gui gui, TitleBar titleBar) {
        status = new StatusBar(gui).slot(Landmarks.STATUS_RUN, StatusBar.Side.LEFT, "");
        gui.landmark(Landmarks.STATUS, status.node());
        gui.landmark(Landmarks.STATUS_RUN, status.slot(Landmarks.STATUS_RUN));

        // Wider than the lane by View.SLIDE_LANES lanes each side: a picture is clipped to its own node's box where the
        // node is drawn, so a strip only as wide as the lane left the edge it slid away from empty for a whole glide.
        strip = gui.box().width(Length.percent(100f * (1 + 2 * View.SLIDE_LANES))).height(Length.FILL);
        // Clipped but never scrolled: the strip overflows it on purpose, and overflow alone would give it a scrollbar.
        lane = gui.box().width(Length.grow(1f)).height(LANE).clip(true).scroll(false, false)
                .background(gui.theme().color(Role.PANEL))
                .children(strip);
        gui.landmark(Landmarks.LANE_PASSES, lane);
        Node name = gui.text("Passes").width(NAMES).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        Node passes = gui.row().width(Length.FILL).gap(Type.TIGHT).children(name, lane);

        Node timeline = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(Type.TIGHT)
                .children(passes);
        gui.landmark(Landmarks.TIMELINE, timeline);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), timeline, status.node());
    }

    /** A lane: what the view measures to draw the strip for. */
    Node lane() {
        return lane;
    }

    /** The strip in the lane: what the view draws its marks on, and slides. */
    Node strip() {
        return strip;
    }

    /** What the status line says: which run is inspected, or why none is. */
    void status(String message) {
        status.text(Landmarks.STATUS_RUN, message);
    }
}
