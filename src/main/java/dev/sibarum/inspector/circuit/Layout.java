package dev.sibarum.inspector.circuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything in the circuit is, in root ems: a backplane.
 *
 * <p>The chips stand in two columns with the topics' trunks between them, one vertical trunk per topic. A chip's
 * <b>ports</b> (the topics it publishes) and <b>pins</b> (its mailboxes) are rows on its edge facing the trunks, and
 * each row runs straight across to its topic's trunk, where a junction dot joins it. A row that crosses another
 * topic's trunk does not join it. Every route is the same shape, so any number of senders and receivers on a topic
 * draw without ambiguity: out along the sender's row, along the trunk, in along the receiver's row.
 *
 * <p>A message whose sender is not known exactly starts on the trunk instead, at its topmost junction: the topic is
 * certain and the sender is one of the chips declared on it. The framework chip has one port, for every topic the
 * graph does not declare; its trunk joins no pin, and its messages run down it into nothing, because where they go is
 * not declared.
 *
 * <p>Built once per graph, which is when it allocates; reading it allocates nothing.
 */
public final class Layout {

    public static final float CHIP_W = 11f;
    public static final float ROW = 0.95f;
    public static final float TITLE = 1.3f;
    public static final float CHIP_GAP = 0.6f;
    public static final float TRUNK_GAP = 1.2f;
    public static final float SIDE_GAP = 1.4f;

    /** A chip's box, and which column it stands in. */
    public record ChipBox(String name, String label, String lane, boolean right, float x, float y, float w, float h) {
    }

    /**
     * One row on a chip's edge: a port it publishes on, or a pin it receives on.
     *
     * @param chip   the chip's index
     * @param pin    whether it is a mailbox (else a port)
     * @param topic  the topic's index
     * @param label  the method for a pin, the topic for a port
     * @param x      where the row leaves the chip
     * @param y      its height
     */
    public record RowBox(int chip, boolean pin, int topic, String label, float x, float y) {
    }

    private final List<ChipBox> chips = new ArrayList<>();
    private final List<RowBox> rows = new ArrayList<>();
    private final List<String> topics = new ArrayList<>();
    private final float[] trunkX;
    private final float[] trunkTop;
    private final float[] trunkBottom;
    private final Map<String, Integer> topicIndex = new HashMap<>();
    private final Map<String, Integer> pinIndex = new HashMap<>();
    private final Map<String, Integer> chipIndex = new HashMap<>();
    private final int[] pinRow;          // mailbox index -> row
    private final int[] pinTopic;        // mailbox index -> topic
    private final int[] pinSlot;         // mailbox index -> its place among its topic's pins
    private final int[][] topicPins;     // topic -> mailbox indices
    private final int[][] topicSources;  // topic -> chip indices that declare they publish it
    private final int[][] pathOf;        // topic -> (source slot + 1) * pins + pin slot -> path; slot -1 is the trunk
    private final int[] frameworkPath;   // the framework port's run down its trunk, as a one-path array
    private final List<float[]> pathX = new ArrayList<>();
    private final List<float[]> pathY = new ArrayList<>();
    private final List<float[]> pathLen = new ArrayList<>();
    private final float width;
    private final float height;
    private final List<String> mailboxNames = new ArrayList<>();

    public Layout(BusGraph graph) {
        // Chips: the declared ones in the order messages flow through them, then the framework's.
        List<BusGraph.Chip> all = new ArrayList<>(byFlow(graph));
        all.add(new BusGraph.Chip(BusGraph.FRAMEWORK, "-", false));
        topics.addAll(graph.topics());
        topics.add(BusGraph.FRAMEWORK);
        for (int t = 0; t < topics.size(); t++) {
            topicIndex.put(topics.get(t), t);
        }
        int frameworkTopic = topics.size() - 1;

        // Each chip's rows: its ports, in topic order, then its pins.
        List<List<int[]>> chipRows = new ArrayList<>();     // per chip: {pin?1:0, topic, mailbox or -1}
        List<BusGraph.Mailbox> mailboxes = graph.mailboxes();
        for (BusGraph.Chip c : all) {
            List<int[]> r = new ArrayList<>();
            if (c.name().equals(BusGraph.FRAMEWORK)) {
                r.add(new int[] {0, frameworkTopic, -1});
            }
            for (int t = 0; t < frameworkTopic; t++) {
                for (BusGraph.Publishes p : graph.publishes()) {
                    if (p.owner().equals(c.name()) && p.topic().equals(topics.get(t))) {
                        r.add(new int[] {0, t, -1});
                        break;
                    }
                }
            }
            for (int m = 0; m < mailboxes.size(); m++) {
                if (mailboxes.get(m).owner().equals(c.name())) {
                    r.add(new int[] {1, topicIndex.get(mailboxes.get(m).topic()), m});
                }
            }
            chipRows.add(r);
        }

        // Two columns, as even as the chips allow, filled in order.
        float total = 0;
        for (List<int[]> r : chipRows) {
            total += chipHeight(r.size()) + CHIP_GAP;
        }
        float rightX = CHIP_W + SIDE_GAP + topics.size() * TRUNK_GAP + SIDE_GAP - TRUNK_GAP;
        float leftY = 0, rightY = 0;
        trunkX = new float[topics.size()];
        for (int t = 0; t < topics.size(); t++) {
            trunkX[t] = CHIP_W + SIDE_GAP + t * TRUNK_GAP;
        }
        pinRow = new int[mailboxes.size()];
        pinTopic = new int[mailboxes.size()];
        for (int i = 0; i < all.size(); i++) {
            BusGraph.Chip c = all.get(i);
            List<int[]> r = chipRows.get(i);
            float h = chipHeight(r.size());
            boolean right = leftY >= total / 2 - 0.01f && i > 0;
            float x = right ? rightX : 0;
            float y = right ? rightY : leftY;
            chips.add(new ChipBox(c.name(), c.name().equals(BusGraph.FRAMEWORK) ? BusGraph.FRAMEWORK : c.label(),
                    c.lane(), right, x, y, CHIP_W, h));
            chipIndex.put(c.name(), i);
            for (int k = 0; k < r.size(); k++) {
                int[] row = r.get(k);
                boolean pin = row[0] == 1;
                String label = pin ? mailboxes.get(row[2]).method() : topics.get(row[1]);
                float ry = y + TITLE + k * ROW + ROW / 2;
                rows.add(new RowBox(i, pin, row[1], label, right ? x : x + CHIP_W, ry));
                if (pin) {
                    pinRow[row[2]] = rows.size() - 1;
                    pinTopic[row[2]] = row[1];
                }
            }
            if (right) {
                rightY += h + CHIP_GAP;
            } else {
                leftY += h + CHIP_GAP;
            }
        }
        width = rightX + CHIP_W;
        height = Math.max(leftY, rightY) - CHIP_GAP;
        for (int m = 0; m < mailboxes.size(); m++) {
            pinIndex.put(mailboxes.get(m).name(), m);
            mailboxNames.add(mailboxes.get(m).name());
        }

        // Trunks span their junctions.
        trunkTop = new float[topics.size()];
        trunkBottom = new float[topics.size()];
        java.util.Arrays.fill(trunkTop, Float.MAX_VALUE);
        java.util.Arrays.fill(trunkBottom, -Float.MAX_VALUE);
        for (RowBox r : rows) {
            trunkTop[r.topic()] = Math.min(trunkTop[r.topic()], r.y());
            trunkBottom[r.topic()] = Math.max(trunkBottom[r.topic()], r.y());
        }
        trunkBottom[frameworkTopic] = trunkTop[frameworkTopic] + 3 * ROW;

        // Each topic's pins and sources, and a path from every source (and the trunk) to every pin.
        topicPins = new int[topics.size()][];
        topicSources = new int[topics.size()][];
        pinSlot = new int[mailboxes.size()];
        pathOf = new int[topics.size()][];
        for (int t = 0; t < topics.size(); t++) {
            List<Integer> pins = new ArrayList<>();
            for (int m = 0; m < mailboxes.size(); m++) {
                if (pinTopic[m] == t) {
                    pinSlot[m] = pins.size();
                    pins.add(m);
                }
            }
            topicPins[t] = pins.stream().mapToInt(Integer::intValue).toArray();
            List<Integer> sources = new ArrayList<>();
            for (RowBox r : rows) {
                if (!r.pin() && r.topic() == t) {
                    sources.add(r.chip());
                }
            }
            topicSources[t] = sources.stream().mapToInt(Integer::intValue).toArray();
            int n = topicPins[t].length;
            pathOf[t] = new int[(topicSources[t].length + 1) * n];
            for (int s = -1; s < topicSources[t].length; s++) {
                for (int p = 0; p < n; p++) {
                    RowBox to = rows.get(pinRow[topicPins[t][p]]);
                    float[] xs, ys;
                    if (s < 0) {
                        xs = new float[] {trunkX[t], trunkX[t], to.x()};
                        ys = new float[] {trunkTop[t], to.y(), to.y()};
                    } else {
                        RowBox from = port(topicSources[t][s], t);
                        xs = new float[] {from.x(), trunkX[t], trunkX[t], to.x()};
                        ys = new float[] {from.y(), from.y(), to.y(), to.y()};
                    }
                    pathOf[t][(s + 1) * n + p] = add(xs, ys);
                }
            }
        }
        RowBox fw = port(all.size() - 1, frameworkTopic);
        frameworkPath = new int[] {add(new float[] {fw.x(), trunkX[frameworkTopic], trunkX[frameworkTopic]},
                new float[] {fw.y(), fw.y(), trunkBottom[frameworkTopic]})};
    }

    /**
     * The chips in the order messages flow through them: a chip comes after every chip that publishes to one of its
     * mailboxes, so senders stand first and the last receivers last; then by name. A cycle is cut where it closes.
     */
    static List<BusGraph.Chip> byFlow(BusGraph graph) {
        Map<String, Integer> rank = new HashMap<>();
        for (BusGraph.Chip c : graph.chips()) {
            rank.put(c.name(), 0);
        }
        for (int pass = 0; pass < graph.chips().size(); pass++) {
            boolean changed = false;
            for (BusGraph.Mailbox m : graph.mailboxes()) {
                for (BusGraph.Chip from : graph.publishersOf(m.topic())) {
                    int r = rank.get(from.name()) + 1;
                    if (!from.name().equals(m.owner()) && r > rank.get(m.owner()) && r <= graph.chips().size()) {
                        rank.put(m.owner(), r);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        List<BusGraph.Chip> out = new ArrayList<>(graph.chips());
        out.sort(java.util.Comparator.<BusGraph.Chip>comparingInt(c -> rank.get(c.name()))
                .thenComparing(BusGraph.Chip::name));
        return out;
    }

    private static float chipHeight(int rows) {
        return TITLE + Math.max(1, rows) * ROW + 0.3f;
    }

    private RowBox port(int chip, int topic) {
        for (RowBox r : rows) {
            if (r.chip() == chip && !r.pin() && r.topic() == topic) {
                return r;
            }
        }
        throw new IllegalStateException("chip " + chip + " has no port on topic " + topic);
    }

    private int add(float[] xs, float[] ys) {
        float[] len = new float[xs.length];
        for (int i = 1; i < xs.length; i++) {
            len[i] = len[i - 1] + Math.abs(xs[i] - xs[i - 1]) + Math.abs(ys[i] - ys[i - 1]);
        }
        pathX.add(xs);
        pathY.add(ys);
        pathLen.add(len);
        return pathX.size() - 1;
    }

    // ------------------------------------------------------------------ reading

    public List<ChipBox> chips() {
        return chips;
    }

    public List<RowBox> rows() {
        return rows;
    }

    public List<String> topics() {
        return topics;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public float trunkX(int topic) {
        return trunkX[topic];
    }

    public float trunkTop(int topic) {
        return trunkTop[topic];
    }

    public float trunkBottom(int topic) {
        return trunkBottom[topic];
    }

    /** The topic's index, or -1 when the graph does not declare it: the framework's. */
    public int topic(String name) {
        Integer t = topicIndex.get(name);
        return t == null || t == topics.size() - 1 ? -1 : t;
    }

    /** The framework's topic: everything undeclared. */
    public int frameworkTopic() {
        return topics.size() - 1;
    }

    /** The mailbox's index, or -1 when the graph has no such mailbox. */
    public int mailbox(String name) {
        Integer m = pinIndex.get(name);
        return m == null ? -1 : m;
    }

    public int mailboxes() {
        return pinRow.length;
    }

    public String mailboxName(int mailbox) {
        return mailboxNames.get(mailbox);
    }

    /** The chip's index, or -1. */
    public int chip(String name) {
        Integer c = chipIndex.get(name);
        return c == null ? -1 : c;
    }

    /** The row a mailbox is drawn on. */
    public RowBox pin(int mailbox) {
        return rows.get(pinRow[mailbox]);
    }

    public int topicOf(int mailbox) {
        return pinTopic[mailbox];
    }

    /** The topic's mailboxes. Do not change the array. */
    public int[] pins(int topic) {
        return topicPins[topic];
    }

    /** The chips declared as publishing the topic. Do not change the array. */
    public int[] sources(int topic) {
        return topicSources[topic];
    }

    /**
     * The path a message on {@code topic} takes from {@code source} (a chip index, or -1 for "one of the declared
     * publishers": the trunk) to {@code mailbox}, or -1 if {@code source} does not publish it.
     */
    public int path(int topic, int source, int mailbox) {
        int slot = -1;
        if (source >= 0) {
            int[] s = topicSources[topic];
            for (int i = 0; i < s.length; i++) {
                if (s[i] == source) {
                    slot = i;
                }
            }
            if (slot < 0) {
                return -1;
            }
        }
        return pathOf[topic][(slot + 1) * topicPins[topic].length + pinSlot[mailbox]];
    }

    /** The framework port's run down its trunk. */
    public int frameworkPath() {
        return frameworkPath[0];
    }

    public int paths() {
        return pathX.size();
    }

    /** The path's corners, for drawing. Do not change the arrays. */
    public float[] pathXs(int path) {
        return pathX.get(path);
    }

    public float[] pathYs(int path) {
        return pathY.get(path);
    }

    /** Where along {@code path} the fraction {@code p} of its length is: written into {@code out} as x, y. */
    public void at(int path, double p, float[] out) {
        float[] xs = pathX.get(path), ys = pathY.get(path), len = pathLen.get(path);
        float d = (float) (Math.max(0, Math.min(1, p)) * len[len.length - 1]);
        int i = 1;
        while (i < len.length - 1 && len[i] < d) {
            i++;
        }
        float seg = len[i] - len[i - 1];
        float f = seg <= 0 ? 0 : (d - len[i - 1]) / seg;
        out[0] = xs[i - 1] + (xs[i] - xs[i - 1]) * f;
        out[1] = ys[i - 1] + (ys[i] - ys[i - 1]) * f;
    }
}
