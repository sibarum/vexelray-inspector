package dev.sibarum.inspector;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * What the satellite windows a card pops out into have in common: the window's title bar, then what the card shows,
 * with the whole window to grow and scroll in. The dashboard has one scrollbar, its own, so a card whose contents
 * would scroll shows what fits and opens the rest here ({@link CursorWindow}, {@link TopicsWindow},
 * {@link ReplayWindow}; the graph's, {@link GraphWindow}, is the precedent).
 */
final class PanelWindow {

    /** A panel window's size on its first opening; its placement is remembered after. */
    static final int W = 900;
    static final int H = 560;

    /** Fill {@code window}'s tree: its title bar, if the application did not ask for the OS frame, over {@code body}. */
    static Gui fill(Window window, Node... body) {
        Gui gui = window.gui();
        Node column = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(Type.GAP).gap(Type.TIGHT)
                .children(body);
        Node root = gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE));
        if (window.titleBar() != null) {
            root.children(window.titleBar().node(), column);
        } else {
            root.children(column);
        }
        return gui;
    }

    private PanelWindow() {
    }
}
