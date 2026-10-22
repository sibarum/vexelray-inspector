package dev.sibarum.inspector;

import dev.sibarum.inspector.tape.Tape;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The tape on screen: the last {@code --tape.seconds} of rows, one track per lane, ending at show time, so it moves
 * with the movement and the graph and never shows a row before they do.
 *
 * <h2>What it means</h2>
 *
 * <ul>
 *   <li><b>A mark is rows.</b> The tape is cut into slices of time too narrow to tell apart; a slice with any row on a
 *       lane has a mark on that lane's track, taller the more rows it has (by their log, so one row is seen and a
 *       storm does not drown the rest).</li>
 *   <li><b>A ruled line is a frame</b> ({@code frame.present}), drawn across every track when frames are far enough
 *       apart to tell apart; when they are not, the {@code frame} lane's own track shows how dense they are.</li>
 *   <li><b>The right edge is show time</b>, the left edge the tape's reach before it. Under the tracks, the reach the
 *       tape really holds: less than asked for when a storm outran the ring ({@link Tape#RATE}).</li>
 * </ul>
 *
 * <h2>How it is drawn</h2>
 *
 * The strip is {@link #SEGMENTS} segments wide, each one {@link Picture}. A segment's picture is built on the
 * offload lane, once when show time has passed it and every {@link #REFRESH} while show time is inside it, and set
 * with one prop write. Inside the frame the segments are only translated, so the frame loop builds nothing.
 *
 * <h2>Threads</h2>
 *
 * {@link #reset} and {@link #frame} on the timeline. The pictures, the track names and the reach line are written
 * from the offload lane; a write off the GUI thread is applied by the next drain.
 */
final class TapeView {

    /** Segments across the strip. */
    static final int SEGMENTS = 10;

    /** Segment slots: the ones across the strip, the one leaving it and the one entering. */
    private static final int SLOTS = SEGMENTS + 2;

    /** Slices of time a segment is counted in. */
    static final int BINS = 96;

    /** How often the segment show time is in is redrawn. */
    private static final long REFRESH = 50_000_000L;

    /** The strip, in root ems: names, then the tracks. */
    static final float NAMES_W = 5.5f, AREA_W = 42.5f, TRACK_H = 0.8f;

    private static final Color AREA = Color.rgb(0x161b22);
    private static final Color RULE = Color.rgba(1f, 1f, 1f, 0.16f);
    private static final Color[] INKS = {
            Parts.GOLD, Color.rgb(0x8fc7ff), Parts.STEEL, Color.rgb(0x9ad19a),
            Parts.RUBY_LIGHT, Color.rgb(0xc7a6f0), Color.rgb(0xf0b77a), Parts.STEEL_SHADE};

    private static final Picture EMPTY = new Sketch().picture();

    private final Tape tape;
    private final Executor offload;
    private final Node node;
    private final Node reachLine;
    private final Node[] names = new Node[Tape.TRACKS];
    private final Slot[] slots = new Slot[SLOTS];

    /** The segments' size in pixels, from the layout. */
    private volatile double pxW, pxH;

    /** The segment show time is in; written on the timeline, read by the builds. */
    private volatile long current = Long.MIN_VALUE;

    // The timeline's own.
    private long refreshedAt;

    TapeView(Gui gui, Tape tape) {
        this.tape = tape;
        this.offload = gui.offload();
        Node heading = gui.text("THE TAPE").textSize(Length.rem(0.8f)).textColor(gui.theme().color(Role.ACCENT));
        reachLine = gui.text("").font("mono").textSize(Length.rem(0.7f)).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false);
        gui.landmark(Landmarks.TAPE_REACH, reachLine);
        Node header = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(0.8f)).width(Length.FILL)
                .children(heading, reachLine);

        Node nameColumn = gui.column().width(Length.rem(NAMES_W));
        for (int t = 0; t < Tape.TRACKS; t++) {
            names[t] = gui.text("").font("mono").textSize(Length.rem(0.6f)).textColor(INKS[t]).wordWrap(false)
                    .height(Length.rem(TRACK_H));
            gui.landmark(Landmarks.TAPE_TRACK + "." + (t + 1), names[t]);
            nameColumn.append(names[t]);
        }
        float segW = AREA_W / SEGMENTS, h = TRACK_H * Tape.TRACKS;
        Node area = gui.box().size(Length.rem(AREA_W), Length.rem(h)).clip(true).hitInert(true).background(AREA)
                .corner(Length.rem(0.2f));
        gui.landmark(Landmarks.TAPE_TRACKS, area);
        for (int s = 0; s < SLOTS; s++) {
            Node seg = gui.box().size(Length.rem(segW), Length.rem(h)).floatAt(Length.rem(0), Length.rem(0))
                    .hitInert(true);
            slots[s] = new Slot(seg);
            area.append(seg);
        }
        gui.onResizeUi(slots[0].node, l -> {
            pxW = l.rect().w();
            pxH = l.rect().h();
            for (Slot s : slots) {
                s.request();
            }
        });
        Node ruler = gui.row().width(Length.rem(AREA_W)).justify(Justify.SPACE_BETWEEN).children(
                ruler(gui, "−" + seconds(tape.reach())), ruler(gui, "−" + seconds(tape.reach() / 2)),
                ruler(gui, "now"));

        node = gui.column()
                .gap(Length.rem(0.35f))
                .padding(Length.rem(0.8f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .children(header,
                        gui.row().children(nameColumn, area),
                        gui.row().children(gui.box().width(Length.rem(NAMES_W)), ruler));
        gui.landmark(Landmarks.TAPE, node);
    }

    private static Node ruler(Gui gui, String text) {
        return gui.text(text).font("mono").textSize(Length.rem(0.6f)).textColor(gui.theme().color(Role.FAINT));
    }

    Node node() {
        return node;
    }

    /** What it draws, which {@link Show} fills. */
    Tape tape() {
        return tape;
    }

    /** A new run: a blank strip. */
    void reset() {
        current = Long.MIN_VALUE;
        for (Slot s : slots) {
            s.seg = Long.MIN_VALUE;
            s.node.picture(EMPTY);
        }
    }

    /** Move the strip to show time {@code show}, at {@code now} on the wall clock. */
    void frame(long show, long now) {
        if (show == Long.MIN_VALUE) {
            return;
        }
        long len = Math.max(1, tape.reach() / SEGMENTS);
        long k = Math.floorDiv(show, len);
        if (k != current) {
            // The segment show time just left is complete: drawn once more, to its end, and then left alone.
            Slot left = slots[(int) Math.floorMod(current, (long) SLOTS)];
            if (current != Long.MIN_VALUE && left.seg == current) {
                left.upTo = (current + 1) * len;
                left.request();
            }
            current = k;
        }
        for (long j = k - SEGMENTS; j <= k; j++) {
            Slot s = slots[(int) Math.floorMod(j, (long) SLOTS)];
            if (s.seg != j) {
                s.seg = j;
                s.upTo = j < k ? (j + 1) * len : show;
                s.node.picture(EMPTY);
                s.request();
            }
            double x = AREA_W * (1 - (double) (show - j * len) / tape.reach());
            s.node.translate((float) x, 0f);
        }
        if (now - refreshedAt >= REFRESH) {
            refreshedAt = now;
            Slot s = slots[(int) Math.floorMod(k, (long) SLOTS)];
            s.upTo = show;
            s.request();
        }
    }

    /** {@code 10 s}, {@code 2.5 s}, {@code 5 min}. */
    static String seconds(long nanos) {
        double s = nanos / 1e9;
        if (s >= 120 && s % 60 == 0) {
            return (long) (s / 60) + " min";
        }
        return (s == Math.rint(s) ? Long.toString((long) s) : String.valueOf(Math.round(s * 10) / 10.0)) + " s";
    }

    /**
     * One segment's node and the picture being built for it. Built on the offload lane, one build at a time: a
     * request during a build is taken by another pass of the same build, so its counts are never shared.
     */
    private final class Slot implements Runnable {

        final Node node;
        final int[] counts = new int[(Tape.TRACKS + 1) * BINS];
        final AtomicBoolean queued = new AtomicBoolean();
        volatile boolean dirty;

        /** The segment this slot shows, and how far into it: written on the timeline. */
        volatile long seg = Long.MIN_VALUE;
        volatile long upTo;

        Slot(Node node) {
            this.node = node;
        }

        void request() {
            dirty = true;
            if (queued.compareAndSet(false, true)) {
                offload.execute(this);
            }
        }

        @Override
        public void run() {
            while (dirty) {
                dirty = false;
                long k = seg;
                if (k != Long.MIN_VALUE) {
                    Picture p = draw(k, upTo);
                    if (seg == k) {
                        node.picture(p);
                    }
                }
            }
            queued.set(false);
            if (dirty && queued.compareAndSet(false, true)) {
                offload.execute(this);
            }
        }

        /** Segment {@code k}, counted up to {@code upTo}; the newest segment also brings the words up to date. */
        private Picture draw(long k, long upTo) {
            double w = pxW, h = pxH;
            long len = Math.max(1, tape.reach() / SEGMENTS);
            long from = k * len;
            if (w <= 0 || h <= 0 || !tape.bin(from, from + len, upTo, BINS, counts)) {
                return EMPTY;
            }
            if (k == current) {
                words();
            }
            double bw = w / BINS, th = h / Tape.TRACKS;
            Sketch sk = new Sketch().tag("segment");
            int frames = 0;
            for (int b = 0; b < BINS; b++) {
                frames += counts[Tape.TRACKS * BINS + b] > 0 ? 1 : 0;
            }
            if (frames <= BINS / 4) {
                for (int b = 0; b < BINS; b++) {
                    if (counts[Tape.TRACKS * BINS + b] > 0) {
                        sk.fill(b * bw, 0, Math.max(1, bw * 0.25), h, RULE);
                    }
                }
            }
            for (int t = 0; t < Tape.TRACKS; t++) {
                for (int b = 0; b < BINS; b++) {
                    int n = counts[t * BINS + b];
                    if (n == 0) {
                        continue;
                    }
                    double tall = th * Math.min(0.9, 0.3 + 0.12 * Math.log(n));
                    sk.fill(b * bw, t * th + (th - tall) / 2, Math.max(1, bw * 0.7), tall, INKS[t]);
                }
            }
            return sk.picture();
        }
    }

    /**
     * The track names and the reach line, from the offload lane, only when they change. Locked, because two builds
     * can both be of the newest segment as show time crosses into the next; the frame loop never takes it.
     */
    private String said = "";
    private final String[] named = new String[Tape.TRACKS];

    private synchronized void words() {
        int tracks = tape.tracks();
        for (int t = 0; t < tracks; t++) {
            String name = tape.trackName(t);
            if (name != named[t]) {
                named[t] = name;
                names[t].text(name);
            }
        }
        long held = tape.held();
        long early = tape.overwritten();
        String text = "last " + seconds(tape.reach())
                + (early == 0 ? "" : " · holds " + seconds(held) + ": " + GraphView.grouped(early)
                + " rows went early, past " + GraphView.grouped(Tape.RATE) + "/s");
        if (!text.equals(said)) {
            said = text;
            reachLine.text(text);
        }
    }
}
