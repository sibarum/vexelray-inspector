package dev.sibarum.inspector.graph;

import dev.sibarum.inspector.circuit.BusGraph;
import dev.sibarum.inspector.clock.BusRows;
import dev.sibarum.inspector.rows.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The component graph as state: the application's parts, and every flow of messages between them this run
 * (docs/component-graph.md).
 *
 * <h2>Flows</h2>
 *
 * A <b>flow</b> is one topic from one part to another: {@code tactroller.input} from Input devices to the GUI. It is
 * made the first time a message takes it, and kept for the rest of the run in the order it was made, so nothing
 * shown moves about. Each counts its messages ({@link #total}) and keeps a <b>rate</b> that rises by one for each
 * message and decays by {@code e} every {@link #TAU}, in the target's time: messages a second, held over about a
 * second, so a burst stays readable after it has passed. A flow's receiver may be {@link #NOBODY} (published with no
 * subscriber) or {@link #UNKNOWN} (no one says who receives it).
 *
 * <h2>Who sent it, who received it</h2>
 *
 * The sender is read from the publishing thread and the topic ({@link Senders}). The receivers are the named
 * mailboxes the publish enqueued to, which atchung writes on the publisher's thread before the publish row; failing
 * those, the stand-in table ({@link Receivers}).
 *
 * <p>Single-threaded: the timeline's. Nothing is allocated per row once a flow, a mailbox and a thread have each been
 * seen once.
 */
public final class Graph {

    /** The parts, by index. Components follow, one per lane. */
    public static final int HOST = 0, GRAPHICS = 1, INPUT = 2, AUTOMATION = 3, GUI = 4, APP = 5;

    /** How many lanes get a node of their own; the last also holds any beyond it. */
    public static final int COMPONENTS = 4;

    /** The first component's index. */
    public static final int FIRST_COMPONENT = 6;

    /** Every part there can be. */
    public static final int PARTS = FIRST_COMPONENT + COMPONENTS;

    /** A flow's receiver: published, and nobody says who received it. */
    public static final int UNKNOWN = -1;

    /** A flow's receiver: published with no subscriber at all. */
    public static final int NOBODY = -2;

    /** A sender to be read from the component lane of the publishing thread. */
    static final int COMPONENT = -3;

    /** The most flows a run keeps; past it a message is counted in {@link #unplaced}. */
    public static final int FLOWS = 64;

    /** How long a rate takes to fall to 1/e, in the target's nanoseconds. */
    public static final long TAU = 1_000_000_000L;

    /** Told of every message that crosses from one part to another. */
    public interface Wire {

        /** A message went from part {@code from} to part {@code to}. */
        void message(int from, int to);
    }

    private static final Part[] FIXED = {
            new Part("host", "Host OS", "window, focus, display, STDIN",
                    "not on the bus yet: FN-9"),
            new Part("graphics", "Graphics", "Vulkan, presenters, compute",
                    "not on the bus yet: FN-10"),
            new Part("input", "Input devices", "tactroller", ""),
            new Part("automation", "Automation", "the automation port", ""),
            new Part("gui", "GUI subsystem", "dispatcher, reconciler, timers", ""),
            new Part("app", "App logic", "handlers, the app's own code", ""),
    };

    /** The mailboxes a thread has enqueued to since its last publish row: parts, and topics where declared. */
    private static final class Queued {
        final int[] parts = new int[64];
        final String[] topics = new String[64];
        int count;
    }

    private final Wire wire;
    private final Part[] parts = new Part[PARTS];

    // Components: the lane each slot is, and what the declared graph says is on it.
    private final String[] lanes = new String[COMPONENTS];
    private final Map<String, String> declaredLane = new HashMap<>();
    private final Map<String, String> declaredTopic = new HashMap<>();
    private final Map<String, List<String>> onLane = new LinkedHashMap<>();
    private int lanesUsed;

    // Mailboxes seen in enqueue rows, matched without cutting the row's detail.
    private final List<String> mailboxNames = new ArrayList<>();
    private final List<String> mailboxTopics = new ArrayList<>();
    private final List<Integer> mailboxParts = new ArrayList<>();

    private final Map<String, Queued> byThread = new HashMap<>();

    private final int[] from = new int[FLOWS];
    private final int[] to = new int[FLOWS];
    private final String[] topic = new String[FLOWS];
    private final long[] total = new long[FLOWS];
    private final double[] rate = new double[FLOWS];
    private final long[] at = new long[FLOWS];
    private int flows;
    private long unplaced;

    public Graph(Wire wire) {
        this.wire = wire;
        declare(BusGraph.NONE);
    }

    /**
     * The application's declared bus graph (docs/bus-circuit.md): its component lanes get their nodes, named for the
     * components on them, and its mailboxes are placed on their lanes. {@link BusGraph#NONE} for an application that
     * declares none. Starts a new run.
     */
    public void declare(BusGraph graph) {
        declaredLane.clear();
        declaredTopic.clear();
        onLane.clear();
        for (BusGraph.Chip chip : graph.chips()) {
            if (chip.component()) {
                onLane.computeIfAbsent(chip.lane(), l -> new ArrayList<>()).add(simple(chip.name()));
            }
        }
        for (BusGraph.Mailbox m : graph.mailboxes()) {
            for (BusGraph.Chip chip : graph.chips()) {
                if (chip.name().equals(m.owner())) {
                    declaredLane.put(m.name(), chip.lane());
                }
            }
            declaredTopic.put(m.name(), m.topic());
        }
        reset();
    }

    /** A new run: no flows, no components but the declared ones. */
    public void reset() {
        System.arraycopy(FIXED, 0, parts, 0, FIXED.length);
        java.util.Arrays.fill(parts, FIRST_COMPONENT, PARTS, null);
        java.util.Arrays.fill(lanes, null);
        lanesUsed = 0;
        mailboxNames.clear();
        mailboxTopics.clear();
        mailboxParts.clear();
        byThread.clear();
        java.util.Arrays.fill(topic, null);
        flows = 0;
        unplaced = 0;
        for (String lane : onLane.keySet()) {
            slot(lane);
        }
    }

    /** A row has been reached by show time. Rows not on the bus lane are ignored. */
    public void row(Row row) {
        if (!row.lane().equals(BusRows.LANE)) {
            return;
        }
        if (row.kind().equals(BusRows.ENQUEUE)) {
            int m = mailbox(row.detail());
            Queued q = byThread.get(row.thread());
            if (q == null) {
                q = new Queued();
                byThread.put(row.thread(), q);
            }
            if (q.count < q.parts.length) {
                q.parts[q.count] = mailboxParts.get(m);
                q.topics[q.count] = mailboxTopics.get(m);
                q.count++;
            }
        } else if (BusRows.publish(row)) {
            publish(row);
        }
    }

    private void publish(Row row) {
        String name = row.kind();
        int sender = Senders.of(row.thread(), name);
        if (sender == COMPONENT) {
            sender = laneOf(row.thread());
        }
        Queued q = byThread.get(row.thread());
        boolean placed = false;
        if (q != null) {
            for (int i = 0; i < q.count; i++) {
                String t = q.topics[i];
                if (t != null && !t.equals(name) || seenBefore(q.parts, i)) {
                    continue;
                }
                flow(sender, q.parts[i], name, row.monoNanos());
                placed = true;
            }
            q.count = 0;
        }
        if (!placed) {
            flow(sender, unsubscribed(row.detail()) ? NOBODY : Receivers.of(name), name, row.monoNanos());
        }
    }

    /** Whether {@code parts[i]} came earlier in the list too, so one publish to two mailboxes on a lane is one flow. */
    private static boolean seenBefore(int[] parts, int i) {
        for (int j = 0; j < i; j++) {
            if (parts[j] == parts[i]) {
                return true;
            }
        }
        return false;
    }

    /** A publish with no subscriber is a count ({@code 1}) rather than a span ({@code 3.1us}). */
    private static boolean unsubscribed(String detail) {
        if (detail.isEmpty()) {
            return false;
        }
        for (int i = 0; i < detail.length(); i++) {
            char c = detail.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void flow(int sender, int receiver, String name, long t) {
        int f = find(sender, receiver, name);
        if (f < 0) {
            if (flows == FLOWS) {
                unplaced++;
                return;
            }
            f = flows++;
            from[f] = sender;
            to[f] = receiver;
            topic[f] = name;
            at[f] = t;
        }
        total[f]++;
        rate[f] = decayed(f, t) + 1;
        at[f] = Math.max(at[f], t);
        if (receiver >= 0 && receiver != sender) {
            wire.message(sender, receiver);
        }
    }

    private int find(int sender, int receiver, String name) {
        for (int f = 0; f < flows; f++) {
            if (from[f] == sender && to[f] == receiver && topic[f].equals(name)) {
                return f;
            }
        }
        return -1;
    }

    private double decayed(int f, long t) {
        long dt = t - at[f];
        return dt <= 0 ? rate[f] : rate[f] * Math.exp(-(double) dt / TAU);
    }

    // ------------------------------------------------------------------ components

    /** The part a component thread ({@code vexel-component-<lane>}) is, giving its lane a node if it has none. */
    private int laneOf(String thread) {
        int start = Senders.COMPONENT_THREAD.length();
        int len = thread.length() - start;
        for (int i = 0; i < lanesUsed; i++) {
            if (lanes[i].length() == len && thread.regionMatches(start, lanes[i], 0, len)) {
                return FIRST_COMPONENT + i;
            }
        }
        return slot(thread.substring(start));
    }

    /** The mailbox an {@code enqueue} row's {@code <mailbox> <depth>} names, learning it the first time. */
    private int mailbox(String detail) {
        int space = detail.indexOf(' ');
        int len = space < 0 ? detail.length() : space;
        for (int m = 0; m < mailboxNames.size(); m++) {
            String name = mailboxNames.get(m);
            if (name.length() == len && detail.regionMatches(0, name, 0, len)) {
                return m;
            }
        }
        String name = detail.substring(0, len);
        String lane = declaredLane.get(name);
        int hash = name.indexOf('#');
        mailboxNames.add(name);
        mailboxTopics.add(declaredTopic.get(name));
        mailboxParts.add(slot(lane != null ? lane : simple(hash < 0 ? name : name.substring(0, hash))));
        return mailboxNames.size() - 1;
    }

    /** The node of {@code lane}: its own, a new one, or the last when every one is taken. */
    private int slot(String lane) {
        for (int i = 0; i < lanesUsed; i++) {
            if (lanes[i].equals(lane)) {
                return FIRST_COMPONENT + i;
            }
        }
        if (lanesUsed == COMPONENTS) {
            parts[PARTS - 1] = new Part("component.more", "More components",
                    lanes[COMPONENTS - 1] + " and others", "");
            return PARTS - 1;
        }
        int i = lanesUsed++;
        lanes[i] = lane;
        List<String> on = onLane.get(lane);
        String title = on != null ? String.join(", ", on) : lane;
        String what = lane.equals("<default>") ? "the shared component lane" : "component lane " + lane;
        parts[FIRST_COMPONENT + i] = new Part("component." + lane, title, what, "");
        return FIRST_COMPONENT + i;
    }

    private static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    // ------------------------------------------------------------------ reading

    /** Part {@code i}, or null for a component node not in use. */
    public Part part(int i) {
        return parts[i];
    }

    /** How many flows this run has. */
    public int flows() {
        return flows;
    }

    public int from(int flow) {
        return from[flow];
    }

    public int to(int flow) {
        return to[flow];
    }

    public String topic(int flow) {
        return topic[flow];
    }

    public long total(int flow) {
        return total[flow];
    }

    /** {@code flow}'s messages a second at {@code now}, the target's time. */
    public double rate(int flow, long now) {
        return decayed(flow, now);
    }

    /** The messages a second from {@code sender} to {@code receiver}, over every topic, at {@code now}. */
    public double rate(int sender, int receiver, long now) {
        double r = 0;
        for (int f = 0; f < flows; f++) {
            if (from[f] == sender && to[f] == receiver) {
                r += decayed(f, now);
            }
        }
        return r;
    }

    /** Whether any message has gone from {@code sender} to {@code receiver} this run. */
    public boolean seen(int sender, int receiver) {
        for (int f = 0; f < flows; f++) {
            if (from[f] == sender && to[f] == receiver) {
                return true;
            }
        }
        return false;
    }

    /** Messages {@code part} sent this run, and received when {@code received}. */
    public long total(int part, boolean received) {
        long n = 0;
        for (int f = 0; f < flows; f++) {
            if ((received ? to[f] : from[f]) == part) {
                n += total[f];
            }
        }
        return n;
    }

    /** Messages a second {@code part} sends, or receives when {@code received}, at {@code now}. */
    public double rate(int part, boolean received, long now) {
        double r = 0;
        for (int f = 0; f < flows; f++) {
            if ((received ? to[f] : from[f]) == part) {
                r += decayed(f, now);
            }
        }
        return r;
    }

    /** Messages not placed on any flow because the run already had {@link #FLOWS}. */
    public long unplaced() {
        return unplaced;
    }
}
