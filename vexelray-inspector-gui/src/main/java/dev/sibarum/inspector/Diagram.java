package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The graph panel's picture: the run's declared parts, in tiers by where they run, and an arrow from each part that
 * sends to each part that hears what it sends, drawn because it is declared, faint until a message crosses it
 * (design.md section 4). Pure: a function of the flows and the panel's size, so a test reads it as marks.
 *
 * <p>The tiers go the way messages mostly do, from outside in, top to bottom: the framework (what widgets, the window
 * and input send, and the receivers no part declares), sources and destinations, jobs, other senders, the components,
 * then the views. An arrow carries every topic one part sends another, and is as thick as the log of what the sender
 * wrote on them; one something crossed names its topics, and the counts are the flows table's, beside it.
 */
final class Diagram {

    /** The text's size, in px. */
    static final float TEXT_PX = 12f;

    /** A part's box: its height, and the room beside it. */
    private static final float BOX_H = 22f;
    private static final float GAP = 12f;
    /** How far apart two arrows between the same two parts, one each way, are drawn. */
    private static final float APART = 5f;

    /** The colours: a part's box and name, an arrow something crossed, one nothing has, and the topics on it. */
    record Inks(Color box, Color name, Color lit, Color faint, Color label) {
    }

    /** A part's box, where it is laid out. */
    record Box(String part, String label, float x, float y, float w, float h) {

        float midX() {
            return x + w / 2f;
        }

        float midY() {
            return y + h / 2f;
        }
    }

    /**
     * An arrow: from one part to another, the topics it carries, and what the sender wrote on them; {@code lit} when
     * that is more than nothing.
     */
    record Arrow(String from, String to, List<String> topics, long written) {

        boolean lit() {
            return written > 0;
        }
    }

    /** Where everything goes: the boxes, by part, and the arrows between them. */
    record Layout(Map<String, Box> boxes, List<Arrow> arrows) {
    }

    private Diagram() {
    }

    /** The tier a part stands in, by its kind: see the class comment. */
    static int tier(Part p) {
        return switch (p.kind()) {
            case "source", "destination" -> 1;
            case "job" -> 2;
            case "sender" -> 3;
            case "component" -> 4;
            case "view" -> 5;
            default -> 0;
        };
    }

    /** The parts and arrows of {@code f}, laid out in a panel {@code w} by {@code h} px. */
    static Layout lay(Flows f, float w, float h) {
        TreeMap<Integer, List<String>> tiers = new TreeMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Part p : f.parts()) {
            tiers.computeIfAbsent(tier(p), c -> new ArrayList<>()).add(p.name());
            boolean own = List.of("logic", "gui", "own", "job", "-").contains(p.lane());
            labels.put(p.name(), Model.typeName(p.name()) + (own ? "" : " · " + p.lane()));
        }
        // Each arrow, by sender and receiver; a mailbox (View#timeline) is its part's.
        Map<String, Arrow> arrows = new LinkedHashMap<>();
        boolean framework = false;
        for (Flow flow : f.flows()) {
            // A topic heard and sent by no declared part is the framework's: a widget's, the window's (bus-graph.md).
            List<End> senders = flow.from().isEmpty() && !flow.to().isEmpty()
                    ? List.of(new End(End.FRAMEWORK, "sends", 0L)) : flow.from();
            for (End from : senders) {
                String sender = labels.containsKey(from.part()) ? from.part() : End.FRAMEWORK;
                framework |= sender.equals(End.FRAMEWORK);
                for (End to : flow.to()) {
                    String receiver = partOf(to.part());
                    if (!labels.containsKey(receiver)) {
                        receiver = End.FRAMEWORK;
                        framework = true;
                    }
                    if (receiver.equals(sender)) {
                        continue;
                    }
                    String key = sender + ">" + receiver;
                    Arrow before = arrows.get(key);
                    List<String> topics = new ArrayList<>(before == null ? List.of() : before.topics());
                    String topic = Model.typeName(flow.topic());
                    if (!topics.contains(topic)) {
                        topics.add(topic);
                    }
                    long written = (before == null ? 0L : before.written()) + Math.max(0L, from.count());
                    arrows.put(key, new Arrow(sender, receiver, List.copyOf(topics), written));
                }
            }
        }
        if (framework) {
            tiers.computeIfAbsent(0, c -> new ArrayList<>()).add(End.FRAMEWORK);
            labels.put(End.FRAMEWORK, End.FRAMEWORK);
        }
        Map<String, Box> boxes = new LinkedHashMap<>();
        int n = tiers.size();
        float tierH = n == 0 ? 0f : h / n;
        int i = 0;
        for (List<String> parts : tiers.values()) {
            List<String> sorted = parts.stream().sorted().toList();
            // A tier between two others keeps a slot spare at its right, so its boxes stand off the line an arrow
            // from the tier above to the one below would take through the middle.
            boolean between = i > 0 && i < n - 1;
            float slot = w / (sorted.size() + (between ? 1 : 0));
            float y = i * tierH + (tierH - BOX_H) / 2f;
            for (int j = 0; j < sorted.size(); j++) {
                String label = labels.get(sorted.get(j));
                float bw = Math.min(label.length() * TEXT_PX * 0.55f + 16f, slot - GAP);
                boxes.put(sorted.get(j), new Box(sorted.get(j), label, slot * j + (slot - bw) / 2f, y, bw, BOX_H));
            }
            i++;
        }
        return new Layout(boxes, List.copyOf(arrows.values()));
    }

    /** A receiver's part: a mailbox, {@code class#method}, is its class's. */
    static String partOf(String receiver) {
        int hash = receiver.indexOf('#');
        return hash < 0 ? receiver : receiver.substring(0, hash);
    }

    /**
     * The picture of {@code f} in a panel {@code w} by {@code h} px: a box a part, and an arrow a pair of parts, from
     * the edge of the sender's box that faces the receiver to the receiver's, with the topics a lit one carries on it.
     */
    static Picture draw(Flows f, float w, float h, Inks inks) {
        Sketch s = new Sketch().tag("graph");
        if (w <= 0f || h <= 0f) {
            return s.picture();
        }
        Layout l = lay(f, w, h);
        // Faint arrows first, so a lit one crossing it is drawn over it.
        List<Arrow> ordered = new ArrayList<>(l.arrows());
        ordered.sort((a, b) -> Boolean.compare(a.lit(), b.lit()));
        for (Arrow a : ordered) {
            Box from = l.boxes().get(a.from());
            Box to = l.boxes().get(a.to());
            if (from == null || to == null) {
                continue;
            }
            float[] ends = ends(from, to);
            // Two arrows between the same pair, one each way, sit a little apart, each on its own side.
            boolean back = a.from().compareTo(a.to()) > 0;
            float dx = from.y() == to.y() ? 0f : back ? -APART : APART;
            float dy = from.y() == to.y() ? (back ? -APART : APART) : 0f;
            float x0 = ends[0] + dx;
            float y0 = ends[1] + dy;
            float x1 = ends[2] + dx;
            float y1 = ends[3] + dy;
            Color ink = a.lit() ? inks.lit() : inks.faint();
            float thick = a.lit() ? (float) Math.min(4.0, 1.0 + Math.log10(1.0 + a.written())) : 1f;
            s.line(x0, y0, x1, y1, thick, ink);
            head(s, x0, y0, x1, y1, thick, ink);
            if (a.lit()) {
                String said = a.topics().size() <= 2 ? String.join(", ", a.topics())
                        : a.topics().get(0) + ", " + a.topics().get(1) + " +" + (a.topics().size() - 2);
                // A third of the way along, beside the line on its own side.
                float lx = x0 + (x1 - x0) * 0.35f + (dx < 0 ? -said.length() * TEXT_PX * 0.47f - 4f : 4f);
                float ly = y0 + (y1 - y0) * 0.35f + TEXT_PX * 0.3f;
                s.text(said, lx, ly, TEXT_PX * 0.85f, inks.label());
            }
        }
        for (Box b : l.boxes().values()) {
            s.outline(b.x(), b.y(), b.w(), b.h(), 3f, 1f, inks.box());
            s.text(fit(b.label(), b.w()), b.x() + 8f, b.midY() + TEXT_PX * 0.35f, TEXT_PX, inks.name());
        }
        return s.picture();
    }

    /** Where an arrow from {@code from} to {@code to} begins and lands: the edges of their boxes that face. */
    static float[] ends(Box from, Box to) {
        if (from.y() == to.y()) {
            boolean right = to.x() > from.x();
            return new float[]{right ? from.x() + from.w() : from.x(), from.midY(),
                    right ? to.x() : to.x() + to.w(), to.midY()};
        }
        boolean down = to.y() > from.y();
        return new float[]{from.midX(), down ? from.y() + from.h() : from.y(),
                to.midX(), down ? to.y() : to.y() + to.h()};
    }

    /** The arrow's head where it lands: two strokes back along it. */
    private static void head(Sketch s, float x0, float y0, float x1, float y1, float thick, Color ink) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        if (len < 1.0) {
            return;
        }
        double ux = (x1 - x0) / len;
        double uy = (y1 - y0) / len;
        double size = 7.0;
        s.line(x1, y1, x1 - size * ux + size * 0.5 * uy, y1 - size * uy - size * 0.5 * ux, thick, ink);
        s.line(x1, y1, x1 - size * ux - size * 0.5 * uy, y1 - size * uy + size * 0.5 * ux, thick, ink);
    }

    /**
     * {@code label} cut to fit {@code w} px of text: its lane let go first, as the part's name says more, then the name,
     * with an ellipsis.
     */
    static String fit(String label, float w) {
        int room = (int) ((w - 12f) / (TEXT_PX * 0.55f));
        if (label.length() <= room || room < 2) {
            return label;
        }
        int lane = label.indexOf(" · ");
        if (lane > 0) {
            return fit(label.substring(0, lane), w);
        }
        return label.substring(0, room - 1) + "…";
    }
}
