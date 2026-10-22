package dev.sibarum.inspector.graph;

/**
 * Who sent a message, read from the thread that published it and its topic: the rules of docs/component-graph.md,
 * first match wins. Until a publish names its sender (framework-notes.md FN-8), the thread is all there is.
 */
final class Senders {

    static final String COMPONENT_THREAD = "vexel-component-";

    private Senders() {
    }

    /** The part that sent {@code topic} from {@code thread}: a part, or {@link Graph#COMPONENT} for a lane's. */
    static int of(String thread, String topic) {
        if (thread.startsWith("vexelray-automation")) {
            return Graph.AUTOMATION;
        }
        if (thread.startsWith(COMPONENT_THREAD)) {
            return Graph.COMPONENT;
        }
        if (thread.startsWith("vexel-handler-") || thread.startsWith("vexelray-gui-handler")) {
            return Graph.APP;
        }
        if (topic.startsWith("vexelray.engine.")) {
            return Graph.GRAPHICS;
        }
        if (topic.equals("tactroller.input")) {
            return Graph.INPUT;
        }
        if (topic.startsWith("vexelray.") || topic.startsWith("tactroller.")) {
            return Graph.GUI;
        }
        return Graph.APP;
    }
}
