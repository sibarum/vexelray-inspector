package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Part;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run declares about its messages: its bus graph, version 2 ({@code docs/bus-graph.md}), read from the text its
 * link answers to {@code bus graph}. Replaced, never changed.
 *
 * @param parts every part, in the text's order
 * @param ends  every way a part hears a topic, and every topic it sends, in the text's order
 * @param kinds each topic's kind, by its name: {@code state}, {@code edge}, {@code edge keyed} or {@code ?}
 * @param why   why there is no graph, in words; empty when there is one
 */
public record Declared(List<Part> parts, List<Line> ends, Map<String, String> kinds, String why) {

    /** The header this Inspector reads. */
    static final String HEADER = "# vexelray bus graph 2";

    /** Nothing said yet. */
    static final Declared NONE = new Declared(List.of(), List.of(), Map.of(), "");

    /**
     * One end of a topic: a part that hears it, and how ({@code mailbox}, {@code inbox}, {@code request},
     * {@code start}, {@code stop}, {@code open}, {@code write}), or that sends it ({@link #SENDS}).
     */
    public record Line(String part, String topic, String how) {

        /** The {@code how} of a part that sends the topic. */
        public static final String SENDS = "sends";

        public boolean sends() {
            return how.equals(SENDS);
        }
    }

    public Declared {
        parts = List.copyOf(parts);
        ends = List.copyOf(ends);
        kinds = Map.copyOf(kinds);
    }

    /** No graph, and why, in words. */
    static Declared none(String why) {
        return new Declared(List.of(), List.of(), Map.of(), why);
    }

    /** Whether the run has said its graph, or why it has none. */
    boolean said() {
        return !parts.isEmpty() || !ends.isEmpty() || !kinds.isEmpty() || !why.isEmpty();
    }

    /**
     * The graph {@code text} says, or none and why: a text of another version, or none. A line of a kind this version
     * does not have is passed over, so a later version that adds lines is still read for what this one knows.
     */
    static Declared parse(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            return none(lines.isEmpty() ? "it declared an empty bus graph"
                    : "its bus graph is not one this Inspector reads (" + lines.getFirst() + ")");
        }
        List<Part> parts = new ArrayList<>();
        List<Line> ends = new ArrayList<>();
        Map<String, String> kinds = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] w = line.split(" ");
            switch (w[0]) {
                case "part" -> {
                    if (w.length >= 4) {
                        parts.add(new Part(w[1], w[2], value(w, "lane"), List.of(w).contains("timer")));
                    }
                }
                case "hears" -> {
                    if (w.length >= 3) {
                        ends.add(new Line(w[1], value(w, "topic"), value(w, "by")));
                    }
                }
                case "sends" -> {
                    if (w.length >= 3) {
                        ends.add(new Line(w[1], value(w, "topic"), Line.SENDS));
                    }
                }
                case "topic" -> {
                    if (w.length >= 3) {
                        String kind = value(w, "kind");
                        kinds.put(w[1], List.of(w).contains("keyed") ? kind + " keyed" : kind);
                    }
                }
                default -> {
                    // a line of a later version
                }
            }
        }
        return new Declared(parts, ends, kinds, "");
    }

    /** The value of {@code key=...} among {@code words}; empty when there is none. */
    private static String value(String[] words, String key) {
        for (String w : words) {
            if (w.startsWith(key + "=")) {
                return w.substring(key.length() + 1);
            }
        }
        return "";
    }
}
