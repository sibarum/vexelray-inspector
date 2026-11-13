package dev.sibarum.inspector;

import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * The graph window: the graph panel's picture with a window to itself, where it can be zoomed and moved
 * ({@link GraphView}). A satellite of the main window, declared with the trees and claimed by the framework, opened by
 * the graph panel's button or its chord as a {@code WindowCommand}; its placement is remembered.
 *
 * <p>Its title bar, the stage the graph is drawn on, and a line under it saying how to look around it. Holds no
 * state, as {@link Ui} does not.
 */
final class GraphWindow {

    /** The window's name, which a {@code WindowCommand} and its widgets' addresses use. */
    static final String KEY = "graph";
    static final String TITLE = "Bus graph";
    static final int W = 960;
    static final int H = 640;

    private final Gui gui;
    private final Node stage;
    private final PauseButton pause;

    GraphWindow(Window window) {
        gui = window.gui();
        stage = gui.box().width(Length.FILL).height(Length.grow(1f)).clip(true).scroll(false, false)
                .background(gui.theme().color(Role.PANEL));
        gui.landmark(Landmarks.GRAPH_STAGE, stage);
        Node hint = gui.text("Wheel or pinch to zoom, drag to move, double-click to fit")
                .width(Length.FILL).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        Node body = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(Type.TIGHT).gap(Type.TIGHT)
                .children(stage, hint);
        pause = new PauseButton(gui);
        gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE))
                .children(PanelWindow.barred(window, pause), body);
    }

    /** The window's tree, which its view hears the gestures on. */
    Gui gui() {
        return gui;
    }

    /** The title bar's pause button. */
    PauseButton pause() {
        return pause;
    }

    /** The box the graph is drawn in. */
    Node stage() {
        return stage;
    }
}
