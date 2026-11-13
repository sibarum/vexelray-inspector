package dev.sibarum.inspector;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * The topics window: every topic, as the Topics card shows its first few, with room to scroll. Opened by the card's
 * pop-out button; {@link TopicsView} fills it. Holds no state.
 */
final class TopicsWindow {

    /** The window's name, which a {@code WindowCommand} and its widgets' addresses use. */
    static final String KEY = "topics";
    static final String TITLE = "Topics";

    private final Gui gui;
    private final PauseButton pause;
    private final Topics topics;
    private final Node why;

    TopicsWindow(Window window) {
        Gui tree = window.gui();
        topics = new Topics(tree, Landmarks.TOPICS_TABLE, Landmarks.TOPICS_BODY, Integer.MAX_VALUE);
        topics.node().width(Length.FILL).height(Length.grow(1f));
        why = tree.text("").width(Length.FILL).textColor(tree.theme().color(Role.DIM)).textSize(Type.SMALL);
        why.visible(false);
        pause = new PauseButton(tree);
        gui = PanelWindow.fill(window, pause, why, topics.node());
    }

    Gui gui() {
        return gui;
    }

    PauseButton pause() {
        return pause;
    }

    Topics topics() {
        return topics;
    }

    /** Say {@code note} over the table, as the card does; empty hides it. */
    void why(String note) {
        why.text(note);
        why.visible(!note.isEmpty());
    }
}
