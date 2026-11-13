package dev.sibarum.inspector;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;

/**
 * The cursor window: every detail of the cursor's moment, as the Cursor card shows them, with room for a click's path
 * of thirty hops to scroll. Opened by the card's pop-out button; {@link CursorView} fills it. Holds no state.
 */
final class CursorWindow {

    /** The window's name, which a {@code WindowCommand} and its widgets' addresses use. */
    static final String KEY = "cursor";
    static final String TITLE = "Cursor";

    private final Gui gui;
    private final PauseButton pause;
    private final DetailsList details;

    CursorWindow(Window window) {
        Gui tree = window.gui();
        details = new DetailsList(tree, true, false);
        tree.landmark(Landmarks.CURSOR_DETAILS, details.node());
        pause = new PauseButton(tree);
        gui = PanelWindow.fill(window, pause, details.node());
    }

    Gui gui() {
        return gui;
    }

    PauseButton pause() {
        return pause;
    }

    DetailsList details() {
        return details;
    }
}
