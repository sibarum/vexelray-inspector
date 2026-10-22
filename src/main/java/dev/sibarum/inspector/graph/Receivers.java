package dev.sibarum.inspector.graph;

/**
 * Who receives a topic, when no named mailbox said so: the stand-in table of docs/component-graph.md, written from a
 * survey of the stack on 2026-10-21. It goes when the bus can list its own subscribers (framework-notes.md FN-7).
 */
final class Receivers {

    private Receivers() {
    }

    /** The part {@code topic} is delivered to: a part, {@link Graph#NOBODY}, or {@link Graph#UNKNOWN}. */
    static int of(String topic) {
        if (topic.equals("tactroller.input")            // InputDispatcher, KronoInput
                || topic.equals("vexelray.gui.mutations")  // the reconciler in Gui
                || topic.equals("vexelray.gui.navigate")   // Gui, GuiApp, Popout
                || topic.equals("vexelray.gui.clicks")     // Select, ContextMenu
                || topic.equals("vexelray.gui.focus")) {   // widgets; some applications too, not shown yet
            return Graph.GUI;
        }
        if (topic.equals("vexelray.gui.keys") || topic.equals("vexelray.gesture")
                || topic.startsWith("vexelray.engine.")) {
            return Graph.NOBODY;                          // no subscriber in main code
        }
        return Graph.UNKNOWN;
    }
}
