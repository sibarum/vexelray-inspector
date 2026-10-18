package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.Beat;
import dev.sibarum.inspector.clock.Durations;
import dev.sibarum.inspector.clock.Escapement;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar, the escapement beside its readouts, and a status line saying where the rows
 * come from.
 *
 * <p>It is the {@link Escapement}'s listener, so it is called on the source's thread with the whole new
 * {@link Beat} each time. A prop written off the GUI thread is queued and applied by the next drain, which is the
 * framework's own idiom; nothing here reads the tree back.
 */
final class Ui implements Escapement.Listener {

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Movement movement;
    private final Node inspecting;
    private final Picker picker;
    private final Node frame;
    private final Node frameTime;
    private final Node interval;
    private final Node park;
    private final Node wake;
    private final Node waker;

    Ui(Gui gui, KronoGui krono, TitleBar titleBar, Picker picker) {
        this.titleBar = titleBar;
        this.movement = new Movement(gui, krono);
        this.picker = picker;

        Node readouts = gui.column()
                .width(Length.rem(17))
                .gap(Length.rem(0.9f))
                .padding(Length.rem(1.2f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f));
        Node heading = gui.text("THE MAIN LOOP").textSize(Length.rem(0.8f)).textColor(gui.theme().color(Role.ACCENT));
        readouts.append(heading);
        frame = readout(gui, readouts, "Frame", Landmarks.FRAME);
        frameTime = readout(gui, readouts, "Frame time", Landmarks.FRAME_TIME);
        interval = readout(gui, readouts, "Since the frame before", Landmarks.INTERVAL);
        park = readout(gui, readouts, "Park", Landmarks.PARK);
        wake = readout(gui, readouts, "Last rest ended by", Landmarks.WAKE);
        waker = readout(gui, readouts, "On the thread", Landmarks.WAKER);

        // Two faces of one area: the movement while something is being inspected, the picker while nothing is. Shown
        // and hidden rather than swapped, so each keeps its state (and the picker its entries) between turns.
        inspecting = gui.row()
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .gap(Length.rem(2.5f))
                .children(movement.node(), readouts);
        picker.node().visible(false);
        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .children(inspecting, picker.node());

        status = new StatusBar(gui).slot(Landmarks.STATUS_SOURCE, StatusBar.Side.LEFT, "");
        gui.landmark(Landmarks.STATUS, status.node());
        gui.landmark(Landmarks.STATUS_SOURCE, status.slot(Landmarks.STATUS_SOURCE));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body, status.node());
    }

    /** A name over a value; returns the value. */
    private static Node readout(Gui gui, Node into, String name, String landmark) {
        Node value = gui.text("—").font("mono").textSize(Length.rem(1.15f)).textColor(gui.theme().color(Role.INK));
        gui.landmark(landmark, value);
        into.append(gui.column().gap(Length.rem(0.15f)).children(
                gui.text(name).textSize(Length.rem(0.75f)).textColor(gui.theme().color(Role.FAINT)),
                value));
        return value;
    }

    @Override
    public void tick(Beat beat) {
        movement.tick();
        show(beat);
    }

    @Override
    public void rest(Beat beat) {
        show(beat);
    }

    @Override
    public void wake(Beat beat) {
        show(beat);
    }

    @Override
    public void source(String state) {
        status.text(Landmarks.STATUS_SOURCE, state);
    }

    /** Everything derived from the beat, from the whole of it, so no two readouts can disagree. */
    private void show(Beat beat) {
        movement.show(beat);
        frame.text(beat.frames() == 0 ? "—"
                : "#" + (beat.frame() >= 0 ? beat.frame() : beat.frames()) + "  (" + beat.frames() + " seen)");
        frameTime.text(Durations.text(beat.frameNanos()));
        interval.text(Durations.text(beat.intervalNanos()));
        park.text(beat.budget().isEmpty() ? "—"
                : beat.restsNotAtAll() ? "0ms: polled, no rest"
                : (beat.resting() ? "resting · " : "rested " + Durations.text(beat.restedNanos()) + " of ")
                        + beat.budget());
        wake.text(beat.wake().isEmpty() ? "—" : beat.wake());
        waker.text(beat.waker().isEmpty() ? "—" : beat.waker());
    }

    /** The window's title says what is being inspected. */
    void title(String source) {
        titleBar.title(source + " — " + Inspector.TITLE);
    }

    /** Show the picker (nothing is being inspected) or the movement (something is). Any thread: writes are queued. */
    void picking(boolean picking) {
        inspecting.visible(!picking);
        picker.node().visible(picking);
    }
}
