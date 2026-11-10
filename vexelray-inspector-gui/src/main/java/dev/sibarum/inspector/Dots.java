package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Sketch;

import java.util.ArrayList;
import java.util.List;

/**
 * The dots that run along the graph's lit arrows, as many a second as the sender writes on it, so how busy an arrow
 * is can be seen at a glance. Every dot runs at one speed, {@link #SPEED}, and what a second shows is how close
 * together they come: one dot a message up to {@link #HONEST} a second, then on a log scale up to {@link #MOST_SHOWN}
 * at {@link #TOP_RATE} and over. The rate is shown in steps ({@link #shown}), so a rate that wanders between two
 * batches leaves the dots where they are.
 *
 * <p>The told half ({@link Pool#dots}) lays the dots out on their tracks when the flows or the panel's size change; the
 * asked half ({@link #place}) moves them, from the frame's clock alone, allocating nothing. A dot fades in where its
 * arrow begins and out where it lands. Paused, the dots stop where they are, and go on from there when following
 * again: their clock is the frame's less the time spent paused ({@link #following}). Under reduced motion they stand.
 *
 * @param dots     every dot node the pool has, those on no track among them, faded out
 * @param pausedAt when the dots stopped, on the machine's clock; -1 while they run
 * @param lostNanos how long they have been stopped in all, which their clock is behind the frame's by
 */
record Dots(List<Dot> dots, long pausedAt, long lostNanos) {

    /** No dots, running. */
    static final Dots NONE = new Dots(List.of(), -1L, 0L);

    /** How fast every dot runs, px a second. */
    static final float SPEED = 96f;
    /** A dot's diameter, px, and the box it is drawn in: in the names' ink, so it stands out on a lit arrow. */
    static final float SIZE = 6f;
    static final float BOX = 10f;
    /** How far from either end of its arrow a dot fades over, px. */
    static final float FADE = 10f;
    /** The most dots on one arrow. */
    static final int MOST = 24;
    /** Up to this rate a dot is a message. */
    static final double HONEST = 4.0;
    /** The most dots a second an arrow shows, at {@link #TOP_RATE} a second and over. */
    static final double MOST_SHOWN = 12.0;
    static final double TOP_RATE = 1000.0;

    /**
     * One dot: its node, where its track begins, the way along it, its length, how far along a dot goes before it is
     * back where it began ({@code period}, at least the length), and how far ahead of the track's first dot it is.
     * A dot on no track has length 0.
     */
    record Dot(Node node, float x0, float y0, float ux, float uy, float length, float period, float lead) {
    }

    Dots {
        dots = List.copyOf(dots);
    }

    /** These dots stopped at {@code now}, or running from it, as {@code live} says; as they are if they already are. */
    Dots following(boolean live, long now) {
        if (live == (pausedAt < 0)) {
            return this;
        }
        return live ? new Dots(dots, -1L, lostNanos + (now - pausedAt)) : new Dots(dots, now, lostNanos);
    }

    /** These, on {@code next}'s tracks, stopped or running as these are. */
    Dots laidOut(List<Dot> next) {
        return new Dots(next, pausedAt, lostNanos);
    }

    /** Whether they move: some dot is on a track, and they are not stopped. */
    boolean moving() {
        if (pausedAt >= 0) {
            return false;
        }
        for (int i = 0; i < dots.size(); i++) {
            if (dots.get(i).length() > 0f) {
                return true;
            }
        }
        return false;
    }

    /**
     * The dots a second {@code perSecond} is shown as, in steps: a dot a message, by halves, up to {@link #HONEST};
     * then in whole dots on a log scale to {@link #MOST_SHOWN}. 0 for none.
     */
    static double shown(double perSecond) {
        if (!(perSecond > 0.0)) {
            return 0.0;
        }
        if (perSecond <= HONEST) {
            return Math.max(0.5, Math.round(perSecond * 2.0) / 2.0);
        }
        double over = Math.log(Math.min(perSecond, TOP_RATE) / HONEST) / Math.log(TOP_RATE / HONEST);
        return Math.round(HONEST + over * (MOST_SHOWN - HONEST));
    }

    /**
     * The dots on a track {@code length} px long showing {@code perSecond}: how many, and the period they come round
     * in. Spaced {@code SPEED / shown} apart, or as near that as a whole number of them along the track allows, so the
     * last one leaves as the first comes in; one a period longer than the track when they come further apart than it is
     * long. As {@code {count, period}}; no dots for no rate.
     */
    static float[] spacing(float length, double perSecond) {
        double shown = shown(perSecond);
        if (shown <= 0.0 || length <= 0f) {
            return new float[]{0f, 0f};
        }
        double apart = SPEED / shown;
        if (apart >= length) {
            return new float[]{1f, (float) apart};
        }
        int n = (int) Math.min(MOST, Math.max(1, Math.round(length / apart)));
        return new float[]{n, length};
    }

    /**
     * The asked half: each dot where its clock puts it at {@code t}, the frame's time on the machine's clock, faded at
     * its track's ends; those on no track faded out. {@code still} for reduced motion, where they stand where they
     * began. Allocates nothing.
     */
    static void place(Dots s, long t, boolean still, Drawn out) {
        long clock = still ? 0L : (s.pausedAt() >= 0 ? s.pausedAt() : t) - s.lostNanos();
        double run = clock / 1e9 * SPEED;
        List<Dot> dots = s.dots();
        for (int i = 0; i < dots.size(); i++) {
            Dot d = dots.get(i);
            if (d.length() <= 0f) {
                out.opacity(d.node(), 0f);
                continue;
            }
            float at = (float) ((run + d.lead()) % d.period());
            float fade = at > d.length() ? 0f : Math.min(1f, Math.min(at, d.length() - at) / FADE);
            out.offset(d.node(), d.x0() + d.ux() * at - BOX / 2f, d.y0() + d.uy() * at - BOX / 2f);
            out.opacity(d.node(), fade);
        }
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
         * The dots for {@code tracks}, moved {@code dx, dy} px (the graph window's lens), every node the pool has among
         * them, as {@code before} runs.
         */
        Dots dots(Dots before, List<Diagram.Track> tracks, float dx, float dy) {
            List<Dot> out = new ArrayList<>();
            int used = 0;
            for (Diagram.Track tr : tracks) {
                float length = tr.length();
                float[] spaced = spacing(length, tr.perSecond());
                int n = (int) spaced[0];
                if (n == 0) {
                    continue;
                }
                float ux = (tr.x1() - tr.x0()) / length;
                float uy = (tr.y1() - tr.y0()) / length;
                float apart = n == 1 ? spaced[1] : spaced[1] / n;
                for (int k = 0; k < n; k++) {
                    out.add(new Dot(node(used++), tr.x0() + dx, tr.y0() + dy, ux, uy, length, spaced[1], k * apart));
                }
            }
            for (int i = used; i < nodes.size(); i++) {
                out.add(new Dot(nodes.get(i), 0f, 0f, 0f, 0f, 0f, 1f, 0f));
            }
            return before.laidOut(out);
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
