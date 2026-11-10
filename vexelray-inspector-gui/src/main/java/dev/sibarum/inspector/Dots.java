package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Sketch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The dots on the graph panel's arrows: one for each message on the Messages lane, leaving its sender's box at the
 * moment it was written and landing on each part that hears it {@link #TRANSIT} later. So a dot is where the message
 * would be at the moment the lanes are at: the right edge while following, the cursor when stopped. A burst on the
 * lane leaves as a burst on its arrow, a quiet spell is a gap, and stepping the cursor moves the dots with it.
 *
 * <p>A message is put on an arrow by its type and its sender, the event's {@link Model#FROM}, as the arrows are drawn.
 * Two messages closer together on an arrow than a dot is wide would be drawn one over the other, so only one of them
 * is: by the slot of time its moment falls in ({@link #slot}), which does not depend on which messages are in view, so
 * a dot does not blink as the window moves.
 *
 * <p>The told half ({@link Pool#dots}) lays them out when the timeline, the flows or the panel's size change; the asked
 * half ({@link #place}) puts each where the moment has it, allocating nothing.
 *
 * @param dots     every dot node the pool has, those carrying no message among them, faded out
 * @param pinnedAt the moment the dots stand at while the timeline is stopped, the cursor's; -1 while following, when
 *                 the moment is the lanes' right edge at each frame
 */
record Dots(List<Dot> dots, long pinnedAt) {

    /** No dots. */
    static final Dots NONE = new Dots(List.of(), -1L);

    /** How long a message's dot takes from its sender's box to its receiver's. */
    static final long TRANSIT = 1_000_000_000L;
    /** A dot's diameter, px, and the box it is drawn in: in the names' ink, so it stands out on a lit arrow. */
    static final float SIZE = 6f;
    static final float BOX = 10f;
    /** How far from either end of its arrow a dot fades over, px. */
    static final float FADE = 10f;
    /** The most dots at once, over every arrow. */
    static final int MOST = 600;

    /**
     * One message's dot on one arrow: its node, where the arrow begins, the way along it, its length, and the moment
     * the message was written, on the rows' clock. A node carrying no message has length 0.
     */
    record Dot(Node node, float x0, float y0, float ux, float uy, float length, long sentAt) {
    }

    Dots {
        dots = List.copyOf(dots);
    }

    /** Whether the dots move by themselves: while following, with some on an arrow. */
    boolean moving() {
        if (pinnedAt >= 0) {
            return false;
        }
        for (int i = 0; i < dots.size(); i++) {
            if (dots.get(i).length() > 0f) {
                return true;
            }
        }
        return false;
    }

    /** The moment the dots are at, for the lanes' right edge at {@code edge}: the cursor's when they are pinned. */
    double moment(double edge) {
        return pinnedAt >= 0 ? pinnedAt : edge;
    }

    /**
     * The slot of time on a track {@code length} px long that a message written at {@code sentAt} falls in: a dot's
     * width of travel, so two messages in one slot would be drawn over each other.
     */
    static long slot(long sentAt, float length) {
        long width = Math.max(1L, (long) (TRANSIT * (SIZE / Math.max(length, SIZE))));
        return Math.floorDiv(sentAt, width);
    }

    /**
     * The asked half: each dot where its message is at {@code moment}, on the rows' clock, faded at its arrow's ends;
     * one not yet sent, or landed, or carrying none, faded out. Allocates nothing.
     */
    static void place(Dots s, double moment, Drawn out) {
        List<Dot> dots = s.dots();
        for (int i = 0; i < dots.size(); i++) {
            Dot d = dots.get(i);
            double gone = (moment - d.sentAt()) / TRANSIT;
            if (d.length() <= 0f || gone < 0.0 || gone > 1.0) {
                out.opacity(d.node(), 0f);
                continue;
            }
            float at = (float) (gone * d.length());
            float fade = Math.min(1f, Math.min(at, d.length() - at) / FADE);
            out.offset(d.node(), d.x0() + d.ux() * at - BOX / 2f, d.y0() + d.uy() * at - BOX / 2f);
            out.opacity(d.node(), fade);
        }
    }

    /**
     * Where in {@code marks}, in the order they were written, the first written at or after {@code fromNanos} is, as
     * near as an order a little shuffled by two threads allows.
     */
    static int from(List<Mark> marks, long fromNanos) {
        int lo = 0;
        int hi = marks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (marks.get(mid).fromNanos() < fromNanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** One message's way along one arrow: the track, and the moment the message was written. */
    record Trip(Diagram.Track track, long sentAt) {
    }

    /** How much earlier than its place in the lane a message may have been written: rows from two threads. */
    private static final long SLACK = 250_000_000L;

    /**
     * The trips of the messages among {@code marks}, the Messages lane's events, that are on their way at some moment
     * from {@code fromNanos} to {@code toNanos}: each on every track of {@code tracks} that carries it, and only the
     * first of those in one {@link #slot} of a track. At most {@link #MOST}. Pure, for a test.
     */
    static List<Trip> trips(Diagram.Tracks tracks, List<Mark> marks, long fromNanos, long toNanos) {
        List<Trip> out = new ArrayList<>();
        Set<String> drawn = new HashSet<>();
        long earliest = fromNanos - TRANSIT;
        for (int i = from(marks, earliest - SLACK); i < marks.size() && out.size() < MOST; i++) {
            Mark m = marks.get(i);
            if (m.fromNanos() < earliest || m.fromNanos() > toNanos) {
                continue;
            }
            List<Diagram.Track> on = tracks.carrying(m.field(Model.TYPE), m.field(Model.FROM));
            for (int k = 0; k < on.size() && out.size() < MOST; k++) {
                Diagram.Track tr = on.get(k);
                float length = tr.length();
                if (length > 0f && drawn.add(tracks.all().indexOf(tr) + "@" + slot(m.fromNanos(), length))) {
                    out.add(new Trip(tr, m.fromNanos()));
                }
            }
        }
        return out;
    }

    /**
     * The dot nodes of one panel: floating children of the node the graph is drawn on, made as they are first needed
     * and kept, so a dot's node is only ever moved and faded. Not state: the view's told half keeps it.
     */
    static final class Pool {

        private final Gui gui;
        private final Node parent;
        private final Color ink;
        private final List<Node> nodes = new ArrayList<>();

        Pool(Gui gui, Node parent, Color ink) {
            this.gui = gui;
            this.parent = parent;
            this.ink = ink;
        }

        /**
         * The dots for timeline {@code t}: each message on its Messages lane on its way at the moments the lanes will
         * show before the next timeline, on {@code tracks} moved {@code dx, dy} px. Following, from its right edge on;
         * stopped, at {@code cursor} alone, where they stand.
         */
        Dots dots(Diagram.Tracks tracks, Timeline t, long cursor, float dx, float dy) {
            List<Mark> messages = List.of();
            for (Lane l : t.lanes()) {
                if (l.name().equals(Model.MESSAGES)) {
                    messages = l.marks();
                }
            }
            return t.live() ? dots(tracks, messages, t.edgeNanos(), Long.MAX_VALUE, -1L, dx, dy)
                    : dots(tracks, messages, cursor, cursor, cursor, dx, dy);
        }

        /**
         * The dots of the messages among {@code marks}, the Messages lane's events, that are on their way at some
         * moment from {@code fromNanos} to {@code toNanos}: each on every track of {@code tracks} that carries it,
         * moved {@code dx, dy} px. Pinned at {@code pinnedAt}, or -1 to follow. Every node the pool has is among them.
         */
        Dots dots(Diagram.Tracks tracks, List<Mark> marks, long fromNanos, long toNanos, long pinnedAt, float dx,
                  float dy) {
            List<Dot> out = new ArrayList<>();
            int used = 0;
            for (Trip trip : trips(tracks, marks, fromNanos, toNanos)) {
                Diagram.Track tr = trip.track();
                float length = tr.length();
                out.add(new Dot(node(used++), tr.x0() + dx, tr.y0() + dy, (tr.x1() - tr.x0()) / length,
                        (tr.y1() - tr.y0()) / length, length, trip.sentAt()));
            }
            for (int i = used; i < nodes.size(); i++) {
                out.add(new Dot(nodes.get(i), 0f, 0f, 0f, 0f, 0f, 0L));
            }
            return new Dots(out, pinnedAt);
        }

        private Node node(int i) {
            while (nodes.size() <= i) {
                Node dot = gui.box().width(Length.dp(BOX)).height(Length.dp(BOX)).floatAt(Length.ZERO, Length.ZERO)
                        .hitInert(true).opacity(1f)
                        .picture(new Sketch().tag("dot").circle(BOX / 2f, BOX / 2f, SIZE / 2f, ink).picture());
                parent.append(dot);
                nodes.add(dot);
            }
            return nodes.get(i);
        }
    }
}
