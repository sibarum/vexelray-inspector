package dev.sibarum.inspector;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * The replay window: what running again found and every difference, as the Run again card shows its first few, with
 * room to scroll; a click on one puts the cursor on its tick, as on the card. Opened by the card's pop-out button;
 * {@link ReplayView} fills it. Holds no state.
 */
final class ReplayWindow {

    /** The window's name, which a {@code WindowCommand} and its widgets' addresses use. */
    static final String KEY = "replay";
    static final String TITLE = "Run again";

    private final Gui gui;
    private final Node said;
    private final Places places;

    ReplayWindow(Window window) {
        Gui tree = window.gui();
        said = tree.text("Run again has not been pressed").width(Length.FILL)
                .textColor(tree.theme().color(Role.INK));
        tree.landmark(Landmarks.REPLAY_WINDOW_TEXT, said);
        places = new Places(tree, Landmarks.REPLAY_WINDOW, Landmarks.REPLAY_WINDOW_BODY, View.REPLAY_COLUMNS,
                Ui.REPLAY_WIDTHS, 1.25f, View.REPLAY_LISTED, Ui::replayInk);
        places.node().width(Length.FILL).height(Length.grow(1f));
        gui = PanelWindow.fill(window, said, places.node());
    }

    Gui gui() {
        return gui;
    }

    Places places() {
        return places;
    }

    /** Say what running again found; empty keeps what it said before the first press. */
    void said(String found) {
        if (!found.isEmpty()) {
            said.text(found);
        }
    }
}
