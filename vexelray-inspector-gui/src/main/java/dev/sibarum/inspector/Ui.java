package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Model;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

import java.util.List;

/**
 * The window: the framework's title bar, the timeline, and a status line under both.
 *
 * <h2>The timeline</h2>
 *
 * <p>One lane per clock, each a name beside a band of marks, with the present at the right edge (vexelray-inspector's
 * {@code docs/design.md} section 3), in {@code Model.LANES}' order. The lanes share one time axis, so they are one box
 * that clips, holding one {@link #strip} whose picture the view draws a band a lane: the view slides the strip left as
 * the present moves on, which is the frame contract's asked half, and the box's clip is what keeps the slid strip
 * inside it (a container's clip is anchored, so a child slides within it). The name of the lane the arrows step
 * through is in the text's colour, the others dimmed ({@link #focus}), and the view outlines its band with an
 * overlay on the lanes' box, which the strip's slide does not move.
 *
 * <h2>The details</h2>
 *
 * <p>Right under the lanes, where the eye goes first: a label and a value a line ({@link #details}), what the run's
 * clock said at the cursor and what is known of the event it is on.
 *
 * <h2>The readouts</h2>
 *
 * <p>One line of numbers under the timeline, over the second before the cursor ({@link #readouts}), with the button
 * that follows the present again before it, pressable while the cursor is stopped ({@link #canFollow}).
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

    /** A lane's height, in rem. */
    private static final float LANE_REM = 2.5f;

    private final Gui gui;
    private final StatusBar status;
    private final Node lane;
    private final Node strip;
    private final Node[] names;
    private final Node readouts;
    private final IconButton follow;
    private final Node[] details;

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;
        status = new StatusBar(gui).slot(Landmarks.STATUS_RUN, StatusBar.Side.LEFT, "");
        gui.landmark(Landmarks.STATUS, status.node());
        gui.landmark(Landmarks.STATUS_RUN, status.slot(Landmarks.STATUS_RUN));

        int count = Model.LANES.size();
        // Wider than the lanes by View.SLIDE_LANES lanes each side: a picture is clipped to its own node's box where the
        // node is drawn, so a strip only as wide as the lanes left the edge it slid away from empty for a whole glide.
        strip = gui.box().width(Length.percent(100f * (1 + 2 * View.SLIDE_LANES))).height(Length.FILL);
        // Clipped but never scrolled: the strip overflows it on purpose, and overflow alone would give it a scrollbar.
        lane = gui.box().width(Length.grow(1f)).height(Length.rem(LANE_REM * count)).clip(true).scroll(false, false)
                .background(gui.theme().color(Role.PANEL))
                .children(strip);
        gui.landmark(Landmarks.LANES, lane);
        names = new Node[count];
        for (int i = 0; i < count; i++) {
            names[i] = gui.text(Model.LANES.get(i)).width(NAMES).height(Length.rem(LANE_REM))
                    .textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        }
        Node column = gui.column().width(NAMES).children(names);
        Node lanes = gui.row().width(Length.FILL).gap(Type.TIGHT).children(column, lane);

        readouts = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        gui.landmark(Landmarks.READOUTS, readouts);
        follow = new IconButton(gui, Icon.SKIP_NEXT, "Follow the present").name(Landmarks.FOLLOW)
                .tip("Follow the present (End)");
        canFollow(false);
        gui.landmark(Landmarks.FOLLOW, follow.node());
        Node readoutRow = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(follow.node(), readouts);

        details = new Node[View.DETAILS.size()];
        Node[] detailRows = new Node[details.length];
        for (int i = 0; i < details.length; i++) {
            Node label = gui.text(View.DETAILS.get(i)).width(NAMES).textColor(gui.theme().color(Role.DIM))
                    .textSize(Type.SMALL);
            details[i] = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.INK));
            gui.landmark(Landmarks.detail(View.DETAILS.get(i)), details[i]);
            detailRows[i] = gui.row().width(Length.FILL).gap(Type.TIGHT).children(label, details[i]);
        }
        Node detailsPanel = gui.column().width(Length.FILL).padding(Type.TIGHT).children(detailRows);
        gui.landmark(Landmarks.DETAILS, detailsPanel);

        Node timeline = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(Type.TIGHT).gap(Type.TIGHT)
                .children(lanes, detailsPanel, readoutRow);
        gui.landmark(Landmarks.TIMELINE, timeline);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), timeline, status.node());
        focus(Model.MAIN);
    }

    /** The lanes' box: what the view measures to draw the strip for, a band a lane. */
    Node lane() {
        return lane;
    }

    /** Show {@code name} as the lane the arrows step through. */
    void focus(String name) {
        for (int i = 0; i < names.length; i++) {
            names[i].textColor(gui.theme().color(Model.LANES.get(i).equals(name) ? Role.INK : Role.DIM));
        }
    }

    /** What the details panel says, a value for each of {@code View.DETAILS} in its order. */
    void details(List<String> values) {
        for (int i = 0; i < details.length; i++) {
            details[i].text(values.get(i));
        }
    }

    /** What the readouts line says. */
    void readouts(String line) {
        readouts.text(line);
    }

    /** Whether the follow button can be pressed: while the cursor is stopped. */
    void canFollow(boolean can) {
        follow.enabled(can);
        // Never focused: a focused button claims Space, even disabled, and Space pauses here; End is its key (FN-11).
        gui.focusable(follow.node(), false);
    }

    /** The address the follow button presses with. */
    String followAddress() {
        return follow.address();
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
