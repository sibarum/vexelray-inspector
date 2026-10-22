package dev.sibarum.inspector.graph;

/**
 * One node of the component graph: a part of the application that sends or receives messages
 * (docs/component-graph.md, <i>The parts</i>).
 *
 * @param key     what a landmark calls it: {@code host}, {@code gui}, {@code component.physics}
 * @param title   its name on the graph
 * @param what    one line saying what it is
 * @param missing why it is not on the bus yet, naming the note that would put it there; empty for a live part
 */
public record Part(String key, String title, String what, String missing) {

    /** Whether its messages reach the bus, so the graph can show them. */
    public boolean live() {
        return missing.isEmpty();
    }
}
