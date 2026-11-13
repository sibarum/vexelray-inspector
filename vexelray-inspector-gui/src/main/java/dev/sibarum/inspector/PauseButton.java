package dev.sibarum.inspector;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;

/**
 * The pause button every window wears in its title bar: one per window, all saying the same thing, since each is told
 * the bench's {@code Paused}. It shows what its click would do, a pause mark while the recorder follows the present and
 * a play mark while it is paused, however it came to be paused (Space, a step, a click on the lanes, another window's
 * button). A press is sent to the main view as Space is ({@link Keys.PauseKey}), which knows where the lanes come to
 * rest. Holds only what it last showed.
 */
final class PauseButton {

    static final String PAUSE = "Pause";
    static final String PLAY = "Play";
    static final String PAUSE_TIP = "Pause the recorder (Space)";
    static final String PLAY_TIP = "Play: follow the present again (Space)";

    private final IconButton button;
    private boolean paused;

    PauseButton(Gui gui) {
        button = new IconButton(gui, Icon.PAUSE, PAUSE).name(Landmarks.PAUSE).tip(PAUSE_TIP);
        // In the title bar: its click is the button's, not a window drag.
        button.node().windowRegion(WindowRegion.INTERACTIVE);
        // Never focused: a focused button claims Space, and Space pauses here (FN-11).
        gui.focusable(button.node(), false);
        gui.landmark(Landmarks.PAUSE, button.node());
    }

    Node node() {
        return button.node();
    }

    /** The address the button presses with. */
    String address() {
        return button.address();
    }

    /** Whether it shows the play mark: the recorder is paused, and a click follows again. */
    boolean paused() {
        return paused;
    }

    /** Show the play mark while {@code paused}, the pause mark while not. Changes nothing when it already does. */
    void show(boolean paused) {
        if (paused == this.paused) {
            return;
        }
        this.paused = paused;
        button.icon(paused ? Icon.PLAY : Icon.PAUSE).label(paused ? PLAY : PAUSE).tip(paused ? PLAY_TIP : PAUSE_TIP);
    }
}
