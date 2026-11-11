package dev.sibarum.inspector;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

import java.util.List;

/**
 * The details of the cursor's moment: a label and a value a line, a line for each of {@link View#DETAILS}, shown only
 * while the event at the cursor has that field (the clock's always). On the Cursor card, clipped to the card, and in
 * the cursor window, where a click's path of thirty hops scrolls.
 */
final class DetailsList {

    /** The column the labels stand in. Type-relative, so it grows with the zoom like the labels. */
    static final Length LABELS = Length.rem(7);

    private final Node[] values;
    private final Node[] lines;
    private final Node node;

    /** The lines in a column, clipped; scrolled when {@code scrolls}; each value a landmark when {@code marked}. */
    DetailsList(Gui gui, boolean scrolls, boolean marked) {
        values = new Node[View.DETAILS.size()];
        lines = new Node[values.length];
        for (int i = 0; i < values.length; i++) {
            Node label = gui.text(View.DETAILS.get(i)).width(LABELS).textColor(gui.theme().color(Role.DIM))
                    .textSize(Type.SMALL);
            values[i] = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.INK));
            if (marked) {
                gui.landmark(Landmarks.detail(View.DETAILS.get(i)), values[i]);
            }
            lines[i] = gui.row().width(Length.FILL).gap(Type.TIGHT).children(label, values[i]);
        }
        node = gui.column().width(Length.FILL).height(Length.grow(1f)).clip(true).scroll(false, scrolls)
                .children(lines);
    }

    Node node() {
        return node;
    }

    /** A value for each of {@link View#DETAILS} in its order: a line whose value is empty is hidden. */
    void set(List<String> given) {
        for (int i = 0; i < values.length; i++) {
            values[i].text(given.get(i));
            lines[i].visible(i == 0 || !given.get(i).isEmpty());
        }
    }
}
