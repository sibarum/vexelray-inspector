package dev.sibarum.inspector.circuit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An application's declared message graph, as the processor writes it (docs/bus-circuit.md §1, version 1): the chips,
 * the mailboxes on them, and which chip publishes which topic. Only what the code declares; the framework's
 * hand-wired topics are not in it, and are drawn as one {@link #FRAMEWORK} chip.
 *
 * @param chips     every component and non-component publisher, in the order they are drawn
 * @param mailboxes every {@code @Subscribe} method
 * @param publishes every topic each chip declares it publishes
 */
public record BusGraph(List<Chip> chips, List<Mailbox> mailboxes, List<Publishes> publishes) {

    /** The first line of every graph this reads. */
    public static final String HEADER = "# vexelray bus graph 1";

    /** The chip that stands for everything not declared: the framework's own topics. */
    public static final String FRAMEWORK = "framework";

    /** No declared graph: only the framework chip will be drawn. */
    public static final BusGraph NONE = new BusGraph(List.of(), List.of(), List.of());

    /**
     * A chip.
     *
     * @param name      the class, fully qualified
     * @param lane      a component's lane ({@code <default>} for the shared one), or {@code -} for a publisher that is
     *                  not a component
     * @param component whether it is a {@code @Component}
     */
    public record Chip(String name, String lane, boolean component) {

        /** {@code Worker}: the class's simple name, for a label. */
        public String label() {
            return simple(name);
        }
    }

    /**
     * One {@code @Subscribe} method: a pin on its chip.
     *
     * @param name     {@code class#method}
     * @param topic    the topic's name
     * @param type     the payload class
     * @param overflow what a full mailbox does, as {@code Overflow} names it
     * @param capacity how many it holds
     */
    public record Mailbox(String name, String topic, String type, String overflow, int capacity) {

        /** The chip it is on. */
        public String owner() {
            int hash = name.indexOf('#');
            return hash < 0 ? name : name.substring(0, hash);
        }

        /** {@code job}: the method, for a label. */
        public String method() {
            int hash = name.indexOf('#');
            return hash < 0 ? name : name.substring(hash + 1);
        }
    }

    /** One topic a chip declares it publishes. */
    public record Publishes(String owner, String topic) {
    }

    /** Thrown for a graph this cannot read, with the line it is about. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(int line, String why) {
            super("bus graph line " + line + ": " + why);
        }
    }

    /** Read a graph. Refuses another version, an unknown keyword, a missing pair, a mailbox on an unknown chip. */
    public static BusGraph parse(String text) {
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            throw new Refused(1, "expected '" + HEADER + "', found '" + (lines.length == 0 ? "" : lines[0].trim())
                    + "'");
        }
        Map<String, Chip> chips = new LinkedHashMap<>();
        List<Mailbox> mailboxes = new ArrayList<>();
        List<Publishes> publishes = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] words = line.split(" +");
            if (words.length < 2) {
                throw new Refused(i + 1, "a keyword and a name, at least");
            }
            Map<String, String> pairs = new TreeMap<>();
            for (int w = 2; w < words.length; w++) {
                int eq = words[w].indexOf('=');
                if (eq <= 0) {
                    throw new Refused(i + 1, "'" + words[w] + "' is not key=value");
                }
                pairs.put(words[w].substring(0, eq), words[w].substring(eq + 1));
            }
            String name = words[1];
            String keyword = words[0];
            if (keyword.equals("component")) {
                chips.put(name, new Chip(name, need(pairs, "lane", i), true));
            } else if (keyword.equals("publisher")) {
                chips.put(name, new Chip(name, pairs.getOrDefault("lane", "-"), false));
            } else if (keyword.equals("mailbox")) {
                int capacity;
                try {
                    capacity = Integer.parseInt(need(pairs, "capacity", i));
                } catch (NumberFormatException e) {
                    throw new Refused(i + 1, "capacity is not a number");
                }
                mailboxes.add(new Mailbox(name, need(pairs, "topic", i), need(pairs, "type", i),
                        need(pairs, "overflow", i), capacity));
            } else if (keyword.equals("publishes")) {
                publishes.add(new Publishes(name, need(pairs, "topic", i)));
            } else {
                throw new Refused(i + 1, "unknown keyword '" + keyword + "'");
            }
        }
        for (Mailbox m : mailboxes) {
            if (!chips.containsKey(m.owner())) {
                throw new Refused(0, "mailbox " + m.name() + " is on " + m.owner() + ", which is not a component");
            }
        }
        for (Publishes p : publishes) {
            if (!chips.containsKey(p.owner())) {
                throw new Refused(0, p.owner() + " publishes " + p.topic() + " and is not a chip");
            }
        }
        return new BusGraph(List.copyOf(chips.values()), List.copyOf(mailboxes), List.copyOf(publishes));
    }

    private static String need(Map<String, String> pairs, String key, int line) {
        String v = pairs.get(key);
        if (v == null || v.isEmpty()) {
            throw new Refused(line + 1, "missing " + key + "=");
        }
        return v;
    }

    /** The chips that declare they publish {@code topic}, in chip order. */
    public List<Chip> publishersOf(String topic) {
        List<Chip> out = new ArrayList<>();
        for (Chip c : chips) {
            for (Publishes p : publishes) {
                if (p.owner().equals(c.name()) && p.topic().equals(topic)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** The mailboxes subscribed to {@code topic}. */
    public List<Mailbox> mailboxesOf(String topic) {
        List<Mailbox> out = new ArrayList<>();
        for (Mailbox m : mailboxes) {
            if (m.topic().equals(topic)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Every topic, published or subscribed, in first-seen order: publishes first, then mailboxes. */
    public List<String> topics() {
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (Publishes p : publishes) {
            seen.put(p.topic(), true);
        }
        for (Mailbox m : mailboxes) {
            seen.put(m.topic(), true);
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.keySet()));
    }

    /** Whether {@code topic} is declared at all; one that is not belongs to the framework chip. */
    public boolean declares(String topic) {
        for (Publishes p : publishes) {
            if (p.topic().equals(topic)) {
                return true;
            }
        }
        for (Mailbox m : mailboxes) {
            if (m.topic().equals(topic)) {
                return true;
            }
        }
        return false;
    }

    static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
