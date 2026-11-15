package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.EventSlice;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;

import java.util.List;

/**
 * The events window: a longer stretch of the rows than the Events card's, {@link EventSlice#SLICE} of them, with room
 * to scroll, and Earlier and Later to move the stretch through everything kept. It follows the cursor, as the card
 * does, until paged; Back to the cursor follows it again, as does any move of the cursor. A click on a row puts the
 * cursor on it, as on the card. Opened by the card's pop-out button; {@link EventsView} fills it. Holds no state.
 */
final class EventsWindow {

    /** The window's name, which a {@code WindowCommand} and its widgets' addresses use. */
    static final String KEY = "events";
    static final String TITLE = "Events";

    private final Gui gui;
    private final PauseButton pause;
    private final Node said;
    private final Button earlier;
    private final Button later;
    private final Button follow;
    private final Places places;

    EventsWindow(Window window) {
        Gui tree = window.gui();
        said = tree.text("").width(Length.grow(1f)).textColor(Role.DIM).textSize(Type.SMALL).wordWrap(false);
        tree.landmark(Landmarks.EVENTS_WINDOW_TEXT, said);
        earlier = button(tree, "Earlier", Landmarks.EVENTS_EARLIER);
        later = button(tree, "Later", Landmarks.EVENTS_LATER);
        follow = button(tree, "Back to the cursor", Landmarks.EVENTS_FOLLOW);
        Node bar = tree.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(said, earlier.node(), later.node(), follow.node());
        places = new Places(tree, Landmarks.EVENTS_WINDOW, Landmarks.EVENTS_WINDOW_BODY, View.ROW_COLUMNS,
                Ui.ROW_WIDTHS, 1.25f, EventSlice.SLICE, column -> Role.INK);
        places.node().width(Length.FILL).height(Length.grow(1f));
        pause = new PauseButton(tree);
        gui = PanelWindow.fill(window, pause, bar, places.node());
    }

    /** A ghost button, never focused: a focused button claims Space, which pauses here (FN-11). */
    private static Button button(Gui tree, String label, String landmark) {
        Button b = new Button(tree, label).kind(Button.Kind.GHOST).name(landmark).enabled(false);
        tree.focusable(b.node(), false);
        tree.landmark(landmark, b.node());
        return b;
    }

    Gui gui() {
        return gui;
    }

    PauseButton pause() {
        return pause;
    }

    Places places() {
        return places;
    }

    String earlierAddress() {
        return earlier.address();
    }

    String laterAddress() {
        return later.address();
    }

    String followAddress() {
        return follow.address();
    }

    /**
     * Show a stretch: its rows' {@code cells} in {@code inks}, the cursor's row at {@code selected} (-1 when it is not
     * among them), what the bar says, and which of its buttons can be pressed. When {@code reveal} is a place, scroll it
     * into view.
     */
    void show(List<List<String>> cells, List<Role> inks, int selected, String note, boolean canEarlier,
              boolean canLater, boolean canFollow, int reveal) {
        places.set(cells);
        places.inks(inks);
        // The table's own selection follows the cursor's row, as the card's does; rows chosen with Shift or Ctrl, to
        // copy, stay chosen.
        if (places.table().selection().size() <= 1) {
            if (selected >= 0) {
                places.table().selection().at(selected);
            } else {
                places.table().selection().clear();
            }
        }
        said.text(note);
        earlier.enabled(canEarlier);
        later.enabled(canLater);
        follow.enabled(canFollow);
        // Enabling a button makes it focusable again; it never is here (FN-11).
        gui.focusable(earlier.node(), false);
        gui.focusable(later.node(), false);
        gui.focusable(follow.node(), false);
        if (reveal >= 0) {
            places.table().rows().reveal(reveal);
        }
    }
}
