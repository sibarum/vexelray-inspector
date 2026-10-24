package dev.sibarum.inspector;

import dev.sibarum.inspector.circuit.BusGraph;
import dev.sibarum.inspector.clock.BeadPool;
import dev.sibarum.inspector.clock.ShowClock;
import dev.sibarum.inspector.graph.Graph;
import dev.sibarum.inspector.graph.Part;
import dev.sibarum.inspector.rows.Row;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * The component graph on screen (docs/component-graph.md): the application's parts as boxes, an arrow for each pair
 * of parts a message has crossed between, a dot for each message, and under them the table of every flow.
 *
 * <h2>What it means</h2>
 *
 * <ul>
 *   <li><b>An arrow</b> appears the first time a message crosses it and stays for the run. Its brightness is the
 *       rate over the last second or so ({@link Graph#TAU}), never less than dim once seen.</li>
 *   <li><b>A dot is a message</b>, released when show time reaches it ({@link Show}) and crossing in {@link #TRAVEL}.
 *       Messages closer than the pool's gap ride one dot, which grows with how many it carries.</li>
 *   <li><b>A dashed arrow</b> is a path that exists and does not reach the bus, labelled by the note that would make it
 *       real; a part that is not on the bus is drawn faint and says why.</li>
 *   <li><b>The table</b> lists every flow in the order it was first seen, with its rate now and its total. Rows never
 *       move or vanish in a run; a rate fades instead of dropping out.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * The timeline, inside the frame, writing props and reading nothing back, but for {@link #graph} and {@link #hint}.
 * Every box, arrow and dot is made here, once; nothing is made per message. The readouts format a string when they
 * change, at most ten times a second (docs/TODO.md).
 */
final class GraphView {

    /** Dots that may be in flight at once. */
    static final int DOTS = 256;

    /** One crossing, in wall time. */
    static final long TRAVEL = 700_000_000L;

    /** How often the words and the arrows' brightness are brought up to date. */
    private static final long REFRESH = 100_000_000L;

    /** Rows in the flow table; past them the last row says how many more there are. */
    static final int ROWS = 8;

    /** A row of the table: from, to, topic, its rate a second and a frame, and its total. */
    private static final String TABLE = "%-14s%-14s%-30s%9s%9s%10s";

    /** The board, in root ems. */
    static final float BOARD_W = 48, BOARD_H = 19.5f;

    /** The tachometer's face, in root ems. */
    private static final float TACH_EM = 2.1f;

    private static final float DOT_EM = 0.45f, WIRE_EM = 0.12f, HEAD_EM = 0.7f, SIDE_EM = 0.3f;

    private static final Color BOARD = Color.rgb(0x161b22);
    private static final Color BOX = Color.rgb(0x232a33);
    private static final Color EDGE = Color.rgb(0x4a5563);
    private static final Color LABEL = Color.rgb(0xd5dbe3);
    private static final Color FAINT = Color.rgb(0x8a929e);
    private static final Color WIRE = Color.rgb(0x8fc7ff);
    private static final Color DOT = Color.rgb(0xbfe9ff);
    private static final Color DASH = Color.rgb(0x5d6672);

    /** Each part's box, in board ems: x, y, w, h. */
    private static final float[][] BOXES = new float[Graph.PARTS][];

    static {
        float[] ys = {0.5f, 5.25f, 10.0f, 14.75f};
        BOXES[Graph.HOST] = new float[] {0.5f, ys[0], 10, 4};
        BOXES[Graph.GRAPHICS] = new float[] {0.5f, ys[1], 10, 4};
        BOXES[Graph.INPUT] = new float[] {0.5f, ys[2], 10, 4};
        BOXES[Graph.AUTOMATION] = new float[] {0.5f, ys[3], 10, 4};
        BOXES[Graph.GUI] = new float[] {14.0f, 3.0f, 10.5f, 13.5f};
        BOXES[Graph.APP] = new float[] {28.2f, 7.75f, 9, 4};
        for (int c = 0; c < Graph.COMPONENTS; c++) {
            BOXES[Graph.FIRST_COMPONENT + c] = new float[] {38.8f, ys[c], 8.7f, 4};
        }
    }

    /** Paths that exist and are not on the bus: from, to, and the note on the arrow. */
    private static final int[][] DASHED = {{Graph.HOST, Graph.GUI}, {Graph.GRAPHICS, Graph.GUI}, {Graph.GUI, Graph.APP}};

    private final Gui gui;
    private final Graph graph = new Graph(this::message);
    private final Node node;
    private final Node board;
    private final Node hint;
    private final Node fan;
    private final Node governor;
    private final Node tach;
    private final Node needle;

    // The parts.
    private final Node[] boxes = new Node[Graph.PARTS];
    private final Node[] titles = new Node[Graph.PARTS];
    private final Node[] whats = new Node[Graph.PARTS];
    private final Node[] sent = new Node[Graph.PARTS];
    private final Node[] got = new Node[Graph.PARTS];
    private final Part[] shownPart = new Part[Graph.PARTS];
    private final String[] sentText = new String[Graph.PARTS];
    private final String[] gotText = new String[Graph.PARTS];

    // The arrows: one for every pair a message could cross, hidden until one does.
    private final int[][] wireOf = new int[Graph.PARTS][Graph.PARTS];
    private final int wires;
    private final int[] wireFrom;
    private final int[] wireTo;
    private final float[][] ends;           // x0, y0, x1, y1 in board ems
    private final Node[] lines;
    private final Node[] heads;
    private final float[] shownLevel;

    // The dots.
    private final BeadPool pool;
    private final Node[] dots = new Node[DOTS];
    private final int[] riding = new int[DOTS];

    // The table.
    private final Node[] rows = new Node[ROWS];
    private final String[] rowText = new String[ROWS];

    /** A declared graph waiting for the timeline. */
    private volatile BusGraph next;

    // The timeline's own.
    private long now;
    private long refreshedAt;
    private boolean warm;
    private float fanAngle;
    private long governorAt;
    private float needleAngle = Tachometer.FROM;
    private volatile boolean faceScaled;

    /** The tachometer's size on screen, from the layout pass: written there, read on the timeline. */
    private volatile double tachPx;
    private boolean paused;
    private boolean clearOwed;

    GraphView(Gui gui) {
        this.gui = gui;
        Node heading = gui.text("THE APPLICATION'S PARTS").textSize(Length.rem(0.8f))
                .textColor(gui.theme().color(Role.ACCENT));
        fan = gui.box().size(Length.rem(1.5f), Length.rem(1.5f)).hitInert(true);
        gui.onResizeUi(fan, l -> fan.picture(fanPicture(l.rect().w())));
        needle = gui.box().size(Length.rem(TACH_EM), Length.rem(TACH_EM)).floatAt(Length.rem(0), Length.rem(0))
                .rotate(Tachometer.FROM).hitInert(true);
        gui.onResizeUi(needle, l -> needle.picture(Tachometer.needle(l.rect().w())));
        needle.visible(false);
        tach = gui.box().size(Length.rem(TACH_EM), Length.rem(TACH_EM)).hitInert(true).children(needle);
        gui.onResizeUi(tach, l -> {
            tachPx = l.rect().w();
            tach.picture(Tachometer.face(tachPx, faceScaled));
        });
        gui.landmark(Landmarks.TACHOMETER, tach);
        governor = gui.text(fps(0) + " · " + vsync(-1) + " · ×1 · real time").font("mono").textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.GOVERNOR, governor);
        hint = gui.text("").textSize(Length.rem(0.7f)).textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.GRAPH_HINT, hint);
        Node header = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(0.6f)).width(Length.FILL)
                .children(heading, tach, fan, governor, hint);

        board = gui.box().size(Length.rem(BOARD_W), Length.rem(BOARD_H)).clip(true).hitInert(true)
                .background(BOARD).corner(Length.rem(0.4f));
        gui.landmark(Landmarks.GRAPH_BOARD, board);
        Node dashes = gui.box().size(Length.rem(BOARD_W), Length.rem(BOARD_H)).floatAt(Length.rem(0), Length.rem(0))
                .hitInert(true);
        gui.onResizeUi(dashes, l -> dashes.picture(dashed(l.rect().w() / BOARD_W)));
        board.append(dashes);

        // Every pair a message could cross: from any part to the GUI, the app, or a component.
        int n = 0;
        for (int[] row : wireOf) {
            java.util.Arrays.fill(row, -1);
        }
        for (int f = 0; f < Graph.PARTS; f++) {
            for (int t = Graph.GUI; t < Graph.PARTS; t++) {
                if (f != t) {
                    wireOf[f][t] = n++;
                }
            }
        }
        wires = n;
        wireFrom = new int[n];
        wireTo = new int[n];
        ends = new float[n][];
        lines = new Node[n];
        heads = new Node[n];
        shownLevel = new float[n];
        for (int f = 0; f < Graph.PARTS; f++) {
            for (int t = 0; t < Graph.PARTS; t++) {
                int w = wireOf[f][t];
                if (w < 0) {
                    continue;
                }
                wireFrom[w] = f;
                wireTo[w] = t;
                ends[w] = ends(f, t);
                float[] e = ends[w];
                float dx = e[2] - e[0], dy = e[3] - e[1];
                float len = (float) Math.hypot(dx, dy);
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                lines[w] = gui.box().size(Length.rem(len), Length.rem(WIRE_EM)).background(WIRE)
                        .floatAt(Length.rem((e[0] + e[2] - len) / 2), Length.rem((e[1] + e[3] - WIRE_EM) / 2))
                        .rotate(angle).opacity(0f).hitInert(true);
                Node head = gui.box().size(Length.rem(HEAD_EM), Length.rem(HEAD_EM))
                        .floatAt(Length.rem(e[0] + dx * 0.72f - HEAD_EM / 2), Length.rem(e[1] + dy * 0.72f - HEAD_EM / 2))
                        .rotate(angle).opacity(0f).hitInert(true);
                gui.onResizeUi(head, l -> head.picture(chevron(l.rect().w())));
                heads[w] = head;
                board.append(lines[w]);
                board.append(head);
            }
        }

        for (int p = 0; p < Graph.PARTS; p++) {
            float[] b = BOXES[p];
            titles[p] = gui.text("").textSize(Length.rem(0.7f)).textColor(LABEL).wordWrap(false);
            whats[p] = gui.text("").textSize(Length.rem(0.5f)).textColor(FAINT).wordWrap(false);
            sent[p] = gui.text("").font("mono").textSize(Length.rem(0.52f)).textColor(LABEL).wordWrap(false);
            got[p] = gui.text("").font("mono").textSize(Length.rem(0.52f)).textColor(LABEL).wordWrap(false);
            boxes[p] = gui.column().size(Length.rem(b[2]), Length.rem(b[3])).padding(Length.rem(0.45f))
                    .gap(Length.rem(0.12f)).background(BOX).border(Length.rem(0.06f), EDGE)
                    .corner(Length.rem(0.3f)).clip(true).floatAt(Length.rem(b[0]), Length.rem(b[1])).hitInert(true)
                    .children(titles[p], whats[p], sent[p], got[p]);
            boxes[p].visible(false);
            board.append(boxes[p]);
        }

        pool = new BeadPool(DOTS, TRAVEL, wires, TRAVEL * wires / DOTS);
        for (int i = 0; i < DOTS; i++) {
            dots[i] = gui.box().size(Length.rem(DOT_EM), Length.rem(DOT_EM)).corner(Length.rem(DOT_EM / 2))
                    .background(DOT).floatAt(Length.rem(0), Length.rem(0)).opacity(0f).hitInert(true);
            board.append(dots[i]);
        }

        Node table = gui.column().gap(Length.rem(0.1f)).width(Length.FILL);
        table.append(gui.text(String.format(TABLE, "FROM", "TO", "TOPIC", "A SECOND", "A FRAME", "THIS RUN"))
                .font("mono").textSize(Length.rem(0.6f)).textColor(gui.theme().color(Role.FAINT)).wordWrap(false));
        for (int r = 0; r < ROWS; r++) {
            rows[r] = gui.text("").font("mono").textSize(Length.rem(0.6f))
                    .textColor(gui.theme().color(Role.INK)).wordWrap(false);
            gui.landmark(Landmarks.GRAPH_FLOW + "." + (r + 1), rows[r]);
            table.append(rows[r]);
        }
        gui.landmark(Landmarks.GRAPH_FLOWS, table);

        node = gui.column()
                .gap(Length.rem(0.5f))
                .padding(Length.rem(0.8f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .children(header, board, table);
        gui.landmark(Landmarks.GRAPH, node);
        parts();
        refresh(Long.MIN_VALUE);
    }

    Node node() {
        return node;
    }

    /** What the panel says beside the governor. Any thread. */
    void hint(String text) {
        hint.text(text);
    }

    /** The application's declared bus graph, which names its component lanes; taken at the next frame. Any thread. */
    void graph(BusGraph declared) {
        next = declared;
    }

    /**
     * Paused: the board holds what it shows, dots where they stand and the table as it was, while messages are still
     * counted; going on brings it up to date. On the timeline.
     */
    void paused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            if (clearOwed) {
                clearOwed = false;
                clear();
            }
            refreshedAt = 0;
        }
    }

    /** A new run: no flows, no dots. While paused, the board is cleared on going on. */
    void reset() {
        adopt();
        graph.reset();
        pool.clear();
        if (paused) {
            clearOwed = true;
            return;
        }
        clear();
    }

    private void clear() {
        for (Node d : dots) {
            d.opacity(0f);
        }
        for (int w = 0; w < wires; w++) {
            shownLevel[w] = 0;
            lines[w].opacity(0f);
            heads[w].opacity(0f);
        }
        parts();
        refreshedAt = 0;
    }

    /** A bus or frame-lane row has been reached by show time, at {@code now} on the wall clock. */
    void row(Row row, long now) {
        adopt();
        this.now = now;
        graph.row(row);
    }

    private void adopt() {
        BusGraph g = next;
        if (g != null) {
            next = null;
            graph.declare(g);
            parts();
        }
    }

    /** A message crossed from one part to another: its dot sets off. */
    private void message(int from, int to) {
        int w = wireOf[from][to];
        if (w < 0 || paused) {
            return;
        }
        int slot = pool.spawn(w, true, now);
        riding[slot] = w;
        long weight = pool.weight(slot);
        if (weight == 1) {
            dots[slot].scale(1f);
        } else if ((weight & (weight - 1)) == 0) {
            dots[slot].scale(1f + Math.min(1.2f, (63 - Long.numberOfLeadingZeros(weight)) / 5f));
        }
        if (shownLevel[w] == 0) {
            show(w, 0.3f);
        }
    }

    /** Move everything for the frame at {@code now}, with {@code show} the target's time being shown. */
    boolean frame(long now, long dt, ShowClock clock, long real, long show) {
        adopt();
        this.now = now;
        if (paused) {
            return false;
        }
        boolean moving = false;
        for (int i = 0; i < DOTS; i++) {
            if (!pool.live(i)) {
                continue;
            }
            double p = pool.progress(i, now);
            if (!pool.live(i)) {
                dots[i].opacity(0f);
                continue;
            }
            float[] e = ends[riding[i]];
            dots[i].opacity(1f).translate((float) (e[0] + (e[2] - e[0]) * p - DOT_EM / 2),
                    (float) (e[1] + (e[3] - e[1]) * p - DOT_EM / 2));
            moving = true;
        }
        if (now - refreshedAt >= REFRESH) {
            refreshedAt = now;
            refresh(show);
        }
        boolean dilated = governor(now, dt, clock, real, show);
        return moving || warm || dilated;
    }

    // ------------------------------------------------------------------ the words

    /** Each part's box as the graph has it now: its name, and whether it is on the bus. */
    private void parts() {
        for (int p = 0; p < Graph.PARTS; p++) {
            Part part = graph.part(p);
            if (part == shownPart[p]) {
                continue;
            }
            shownPart[p] = part;
            boxes[p].visible(part != null);
            if (part == null) {
                continue;
            }
            gui.landmark(Landmarks.GRAPH_PART + "." + part.key(), boxes[p]);
            titles[p].text(part.title());
            whats[p].text(part.what());
            boxes[p].opacity(part.live() ? 1f : 0.55f);
            if (!part.live()) {
                int colon = part.missing().indexOf(": ");
                sent[p].text(colon < 0 ? part.missing() : part.missing().substring(0, colon));
                got[p].text(colon < 0 ? "" : part.missing().substring(colon + 2));
            }
            sentText[p] = null;
            gotText[p] = null;
        }
    }

    /** The table, the parts' counts and the arrows' brightness, at show time {@code show}. */
    private void refresh(long show) {
        parts();
        boolean known = show != Long.MIN_VALUE;
        warm = false;
        for (int p = 0; p < Graph.PARTS; p++) {
            Part part = shownPart[p];
            if (part == null || !part.live()) {
                continue;
            }
            say(sent, sentText, p, count("sent", graph.total(p, false), known ? graph.rate(p, false, show) : 0, show));
            say(got, gotText, p, count("got", graph.total(p, true), known ? graph.rate(p, true, show) : 0, show));
        }
        warm |= known && graph.frameRate(show) > 0.05;
        for (int w = 0; w < wires; w++) {
            if (shownLevel[w] == 0 && !graph.seen(wireFrom[w], wireTo[w])) {
                continue;
            }
            double r = known ? graph.rate(wireFrom[w], wireTo[w], show) : 0;
            warm |= r > 0.05;
            show(w, 0.3f + 0.7f * (float) Math.min(1, Math.log1p(r) / Math.log1p(100)));
        }
        int flows = graph.flows();
        for (int r = 0; r < ROWS; r++) {
            String text;
            if (r == ROWS - 1 && flows > ROWS) {
                text = "… and " + (flows - ROWS + 1) + " more flows";
            } else if (r < flows) {
                double rate = known ? graph.rate(r, show) : 0;
                warm |= rate > 0.05;
                text = String.format(TABLE, name(graph.from(r)), name(graph.to(r)), cut(graph.topic(r), 29),
                        perSecond(rate), perFrame(rate, known ? graph.perFrame(rate, show) : Double.NaN),
                        grouped(graph.total(r)));
            } else {
                text = "";
            }
            if (!text.equals(rowText[r])) {
                rowText[r] = text;
                rows[r].text(text);
            }
        }
        if (graph.unplaced() > 0) {
            hint.text(graph.unplaced() + " messages past the table's " + Graph.FLOWS + " flows");
        }
    }

    private void show(int w, float level) {
        if (Math.abs(level - shownLevel[w]) < 0.02f) {
            return;
        }
        shownLevel[w] = level;
        lines[w].opacity(level);
        heads[w].opacity(level);
    }

    private static void say(Node[] nodes, String[] shown, int p, String text) {
        if (!text.equals(shown[p])) {
            shown[p] = text;
            nodes[p].text(text);
        }
    }

    private String count(String verb, long total, double rate, long show) {
        double each = show == Long.MIN_VALUE ? Double.NaN : graph.perFrame(rate, show);
        return total == 0 ? verb + " none"
                : verb + " " + grouped(total) + "  " + perSecond(rate) + " " + perFrame(rate, each);
    }

    /** A flow's end: a part's short name, {@code nobody} or {@code ?}. */
    private String name(int part) {
        if (part == Graph.NOBODY) {
            return "nobody";
        }
        if (part < 0) {
            return "?";
        }
        Part p = graph.part(part);
        String title = p == null ? "?" : part == Graph.INPUT ? "Input" : part == Graph.GUI ? "GUI" : p.title();
        return cut(title, 13);
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** {@code 42/s}, {@code 3.5/s}, or {@code —} for a flow that has gone quiet. */
    static String perSecond(double r) {
        if (r < 0.05) {
            return "—";
        }
        return r >= 10 ? Math.round(r) + "/s" : Math.round(r * 10) / 10.0 + "/s";
    }

    /**
     * {@code 3/f}, {@code 0.7/f}, {@code 0.04/f}: {@code each} messages a frame, for a flow at {@code rate} a second;
     * {@code —} for a flow gone quiet, or before the run's first frame.
     */
    static String perFrame(double rate, double each) {
        if (rate < 0.05 || !(each >= 0.005)) {
            return "—";
        }
        return (each >= 10 ? Long.toString(Math.round(each)) : each >= 1 ? String.valueOf(Math.round(each * 10) / 10.0)
                : String.valueOf(Math.round(each * 100) / 100.0)) + "/f";
    }

    /** {@code 60 fps}, {@code 2.5 fps}, {@code 0 fps}: the target's frames a second; {@code — fps} before any. */
    static String fps(double f) {
        if (!(f > 0)) {
            return "— fps";
        }
        return (f >= 10 ? Long.toString(Math.round(f)) : f < 0.05 ? "0" : String.valueOf(Math.round(f * 10) / 10.0))
                + " fps";
    }

    /** {@code 3,000}. */
    static String grouped(long n) {
        String s = Long.toString(n);
        StringBuilder b = new StringBuilder(s.length() + s.length() / 3);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                b.append(',');
            }
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ geometry and drawing

    /**
     * Where the arrow from part {@code f} to part {@code t} starts and ends, in board ems: centre to centre, moved a
     * little to its right so the arrow back runs beside it, and cut where it leaves one box and enters the other.
     */
    private static float[] ends(int f, int t) {
        float[] a = BOXES[f], b = BOXES[t];
        float ax = a[0] + a[2] / 2, ay = a[1] + a[3] / 2, bx = b[0] + b[2] / 2, by = b[1] + b[3] / 2;
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.hypot(dx, dy);
        float nx = -dy / len * SIDE_EM, ny = dx / len * SIDE_EM;
        ax += nx;
        ay += ny;
        bx += nx;
        by += ny;
        float out = exit(a, ax, ay, dx, dy) + 0.15f / len;
        float in = exit(b, bx, by, -dx, -dy) + 0.15f / len;
        return new float[] {ax + dx * out, ay + dy * out, bx - dx * in, by - dy * in};
    }

    /** How far along {@code (dx, dy)} a line from {@code (x, y)}, inside {@code box}, leaves it. */
    private static float exit(float[] box, float x, float y, float dx, float dy) {
        float tx = dx > 0 ? (box[0] + box[2] - x) / dx : dx < 0 ? (box[0] - x) / dx : Float.MAX_VALUE;
        float ty = dy > 0 ? (box[1] + box[3] - y) / dy : dy < 0 ? (box[1] - y) / dy : Float.MAX_VALUE;
        return Math.min(tx, ty);
    }

    /** The paths that are not on the bus yet, dashed, at {@code pxPerRem}. */
    private static Picture dashed(double pxPerRem) {
        double u = pxPerRem;
        Sketch k = new Sketch().tag("dashed");
        for (int[] d : DASHED) {
            float[] e = ends(d[0], d[1]);
            double dx = e[2] - e[0], dy = e[3] - e[1], len = Math.hypot(dx, dy);
            for (double s = 0; s < len; s += 0.85) {
                double s1 = Math.min(len, s + 0.5);
                k.line((e[0] + dx * s / len) * u, (e[1] + dy * s / len) * u,
                        (e[0] + dx * s1 / len) * u, (e[1] + dy * s1 / len) * u, WIRE_EM * u, DASH);
            }
        }
        float[] e = ends(Graph.GUI, Graph.APP);
        k.text("handler calls", (e[0] + 0.1) * u, (e[1] + 0.85) * u, 0.42 * u, FAINT);
        k.text("FN-11", (e[0] + 0.1) * u, (e[1] + 1.4) * u, 0.42 * u, FAINT);
        return k.picture();
    }

    /** An arrowhead pointing right, in a box {@code s} wide. */
    private static Picture chevron(double s) {
        return new Sketch().tag("head")
                .line(0.25 * s, 0.2 * s, 0.72 * s, 0.5 * s, 0.13 * s, WIRE)
                .line(0.72 * s, 0.5 * s, 0.25 * s, 0.8 * s, 0.13 * s, WIRE)
                .picture();
    }

    // ------------------------------------------------------------------ the governor

    /**
     * The governor: turns while show time is not real time, and says the target's frames a second at show time, how
     * much show time is dilated ({@code ×0.1} is a burst stretched ten times) and how far behind it is.
     */
    private boolean governor(long now, long dt, ShowClock clock, long real, long show) {
        long lag = clock.lag(real);
        double speed = clock.speed();
        boolean dilated = lag > 1_000_000L || Math.abs(speed - 1) > 1e-6;
        if (dilated) {
            fanAngle = (float) ((fanAngle + 360.0 * speed * dt / 1e9) % 360.0);
            fan.rotate(fanAngle);
        }
        double drawn = show == Long.MIN_VALUE ? 0 : graph.frameRate(show);
        long refresh = graph.refreshNanos();
        tachometer(drawn, refresh);
        if (now - governorAt >= 100_000_000L) {
            governorAt = now;
            governor.text(fps(drawn) + " · " + vsync(refresh) + " · ×" + speed(speed) + " · "
                    + (dilated ? lag / 1_000_000L + " ms behind" : "real time"));
        }
        return dilated;
    }

    /**
     * The needle at {@code drawn} frames a second against one refresh of {@code refreshNanos}: no scale and no needle
     * until the target has said its refresh, for a full scale guessed would be a reading made up (FN-14).
     */
    private void tachometer(double drawn, long refreshNanos) {
        boolean scaled = refreshNanos > 0;
        if (scaled != faceScaled) {
            faceScaled = scaled;
            needle.visible(scaled);
            double px = tachPx;
            if (px > 0) {
                tach.picture(Tachometer.face(px, scaled));
            }
        }
        if (!scaled) {
            return;
        }
        float angle = Tachometer.angle(drawn * refreshNanos / 1e9);
        if (Math.abs(angle - needleAngle) >= 0.25f) {
            needleAngle = angle;
            needle.rotate(angle);
        }
    }

    /** {@code of 144 Hz}, or that the target has not said. */
    static String vsync(long refreshNanos) {
        return refreshNanos > 0 ? "of " + Math.round(1e9 / refreshNanos) + " Hz" : "vsync not reported (FN-14)";
    }

    private static String speed(double v) {
        return Math.abs(v - 1) < 1e-6 ? "1" : v >= 10 ? Long.toString(Math.round(v))
                : v >= 1 ? String.valueOf(Math.round(v * 10) / 10.0) : String.valueOf(Math.round(v * 100) / 100.0);
    }

    /** The governor's fan fly: two vanes on an arbor, as a music box's. */
    private static Picture fanPicture(double s) {
        double c = s / 2;
        return new Sketch().tag("fan")
                .line(c - 0.42 * s, c, c + 0.42 * s, c, 0.22 * s, Parts.STEEL)
                .circle(c, c, 0.12 * s, Parts.GOLD)
                .circle(c, c, 0.05 * s, Parts.RUBY)
                .picture();
    }
}
