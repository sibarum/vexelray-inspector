package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.style.Role;
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

    /**
     * The colours: a part's box and name, an arrow something crossed, one nothing has, the topics on it, and an arrow
     * the path of the event the cursor is on crossed.
     */
    record Inks(Color box, Color name, Color lit, Color faint, Color label, Color path) {

        /** The graph's colours in {@code gui}'s theme: the graph panel's and the graph window's are the same. */
        static Inks of(Gui gui) {
            return new Inks(gui.theme().color(Role.LINE), gui.theme().color(Role.INK),
                    gui.theme().color(Role.ACCENT), gui.theme().color(Role.LINE), gui.theme().color(Role.DIM),
                    gui.theme().color(Ui.PATH));
        }
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
     * that is more than nothing. What it wrote a second on them together, and the moment of its newest write on any
     * of them, 0 for none.
     */
    record Arrow(String from, String to, List<String> topics, long written, double perSecond, long lastNanos) {

        Arrow(String from, String to, List<String> topics, long written) {
            this(from, to, topics, written, 0.0, 0L);
        }

        boolean lit() {
            return written > 0;
        }
    }

    /** Where everything goes: the boxes, by part, and the arrows between them. */
    record Layout(Map<String, Box> boxes, List<Arrow> arrows) {
    }

    /**
     * The line an arrow is drawn along, from where it begins to where it lands, with whose it is and what it carries:
     * what a message's dot runs along ({@link Dots}).
     *
     * @param from   the sending part, as the arrow names it: a part with a box, or the framework
     * @param topics the topics it carries, by their names without their packages, as a message event's type is
     */
    record Track(String from, List<String> topics, float x0, float y0, float x1, float y1) {

        float length() {
            return (float) Math.hypot(x1 - x0, y1 - y0);
        }
    }

    /** Every arrow's track, and the parts that have a box: a sender with none is drawn as the framework. */
    record Tracks(java.util.Set<String> parts, List<Track> all) {

        static final Tracks NONE = new Tracks(java.util.Set.of(), List.of());

        /**
         * The tracks a message of type {@code type} written by {@code from} runs along: one for each part that hears
         * it from that sender, as the arrows were drawn ({@link #lay}).
         */
        List<Track> carrying(String type, String from) {
            String sender = parts.contains(from) ? from : End.FRAMEWORK;
            List<Track> out = new ArrayList<>(2);
            for (Track t : all) {
                if (t.from().equals(sender) && t.topics().contains(type)) {
                    out.add(t);
                }
            }
            return out;
        }
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
                    double perSecond = (before == null ? 0.0 : before.perSecond()) + from.perSecond();
                    long last = Math.max(before == null ? 0L : before.lastNanos(), from.lastNanos());
                    arrows.put(key, new Arrow(sender, receiver, List.copyOf(topics), written, perSecond, last));
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
        return draw(f, Traced.NONE, w, h, inks);
    }

    /**
     * Whether arrow {@code a} of layout {@code l} is on path {@code t}: one of its topics was delivered along it, to its
     * receiver's part, or to the framework for a receiver no part of the layout is. A delivery names its receiver and
     * not its sender, so where two parts send a topic to the same one, both arrows are.
     */
    static boolean onPath(Arrow a, Layout l, Traced t) {
        for (Crossed c : t.crossed()) {
            if (!c.to().isEmpty() && a.topics().contains(Model.typeName(c.topic()))) {
                String receiver = partOf(c.to());
                if (!l.boxes().containsKey(receiver)) {
                    receiver = End.FRAMEWORK;
                }
                if (receiver.equals(a.to())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** As {@link #draw(Flows, float, float, Inks)}, the arrows path {@code t} crossed drawn in its colour, named. */
    static Picture draw(Flows f, Traced t, float w, float h, Inks inks) {
        Sketch s = new Sketch().tag("graph");
        if (w <= 0f || h <= 0f) {
            return s.picture();
        }
        Layout l = lay(f, w, h);
        // Faint arrows first, then lit ones, then the path's, so each is drawn over what it crosses.
        List<Arrow> ordered = new ArrayList<>(l.arrows());
        ordered.sort(java.util.Comparator.comparingInt(a -> onPath(a, l, t) ? 2 : a.lit() ? 1 : 0));
        for (Arrow a : ordered) {
            boolean path = onPath(a, l, t);
            float[] line = line(a, l);
            if (line == null) {
                continue;
            }
            float x0 = line[0];
            float y0 = line[1];
            float x1 = line[2];
            float y1 = line[3];
            float dx = line[4];
            Color ink = path ? inks.path() : a.lit() ? inks.lit() : inks.faint();
            float thick = (a.lit() ? (float) Math.min(4.0, 1.0 + Math.log10(1.0 + a.written())) : 1f)
                    + (path ? 1f : 0f);
            s.line(x0, y0, x1, y1, thick, ink);
            head(s, x0, y0, x1, y1, thick, ink);
            if (a.lit() || path) {
                String said = (a.topics().size() <= 2 ? String.join(", ", a.topics())
                        : a.topics().get(0) + ", " + a.topics().get(1) + " +" + (a.topics().size() - 2))
                        + measured(a, f.atNanos());
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

    /**
     * Where arrow {@code a} of layout {@code l} is drawn: from {@code x0, y0} to {@code x1, y1}, and how far it is set
     * off across, {@code dx}, as {@code {x0, y0, x1, y1, dx}}; null when one of its parts has no box. Two arrows between
     * the same pair, one each way, sit a little apart, each on its own side.
     */
    static float[] line(Arrow a, Layout l) {
        Box from = l.boxes().get(a.from());
        Box to = l.boxes().get(a.to());
        if (from == null || to == null) {
            return null;
        }
        float[] ends = ends(from, to);
        boolean back = a.from().compareTo(a.to()) > 0;
        float dx = from.y() == to.y() ? 0f : back ? -APART : APART;
        float dy = from.y() == to.y() ? (back ? -APART : APART) : 0f;
        return new float[]{ends[0] + dx, ends[1] + dy, ends[2] + dx, ends[3] + dy, dx};
    }

    /**
     * What the sender of arrow {@code a} writes on it a second, and how long before {@code atNanos} it last did, in
     * words after its topics: {@code " · 42/s · 0.3 s ago"}. Each is left out when it is not known: no rate, or no
     * write, or no moment the flows are as of.
     */
    static String measured(Arrow a, long atNanos) {
        StringBuilder said = new StringBuilder();
        if (a.perSecond() > 0.0) {
            said.append(" · ").append(a.perSecond() >= 10.0 ? String.format(java.util.Locale.ROOT, "%.0f/s",
                    a.perSecond()) : String.format(java.util.Locale.ROOT, "%.1f/s", a.perSecond()));
        }
        if (a.lastNanos() > 0L && atNanos > 0L) {
            said.append(" · ").append(ago(Math.max(0L, atNanos - a.lastNanos())));
        }
        return said.toString();
    }

    /** {@code nanos} as how long ago: in ms under a second, in tenths of a second under a minute, else whole seconds. */
    static String ago(long nanos) {
        if (nanos < 1_000_000_000L) {
            return nanos / 1_000_000L + " ms ago";
        }
        return nanos < 60_000_000_000L ? String.format(java.util.Locale.ROOT, "%.1f s ago", nanos / 1e9)
                : nanos / 1_000_000_000L + " s ago";
    }

    /** The lines of {@code f}'s arrows, laid out in a panel {@code w} by {@code h} px: where {@link Dots} run. */
    static Tracks tracks(Flows f, float w, float h) {
        if (w <= 0f || h <= 0f) {
            return Tracks.NONE;
        }
        Layout l = lay(f, w, h);
        List<Track> out = new ArrayList<>();
        for (Arrow a : l.arrows()) {
            float[] line = line(a, l);
            if (line != null) {
                out.add(new Track(a.from(), a.topics(), line[0], line[1], line[2], line[3]));
            }
        }
        return new Tracks(java.util.Set.copyOf(l.boxes().keySet()), List.copyOf(out));
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
