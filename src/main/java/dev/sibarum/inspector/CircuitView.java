package dev.sibarum.inspector;

import dev.sibarum.inspector.circuit.BusGraph;
import dev.sibarum.inspector.circuit.Layout;
import dev.sibarum.inspector.circuit.Messages;
import dev.sibarum.inspector.clock.BeadPool;
import dev.sibarum.inspector.clock.Combo;
import dev.sibarum.inspector.clock.ShowClock;
import dev.sibarum.inspector.rows.Row;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.TextLayout;

/**
 * The bus on screen as a circuit: the chips the code declares, a trace for each topic from every chip that publishes
 * it to every mailbox subscribed to it, and an electron for each message, from its sender to each of its receivers.
 * The governor and the combo counter sit over it.
 *
 * <h2>What it means</h2>
 *
 * <ul>
 *   <li><b>Only declared wiring is drawn</b> ({@link BusGraph}, docs/bus-circuit.md). A trace is there because an
 *       {@code @Publishes} and an {@code @Subscribe} say so, never because traffic was seen. The framework's own
 *       topics, which nothing declares, are one {@code framework} chip whose trace leads nowhere.</li>
 *   <li><b>An electron is a message</b> reaching a mailbox, released when show time reaches it ({@link Show}), and
 *       crossing in {@link #TRAVEL}. A message to three mailboxes is three electrons leaving together, splitting at the
 *       junctions. One whose sender is not certain leaves from its trunk instead of a chip. Electrons closer than a
 *       dot's width on one path ride together, and the dot grows with how many it carries.</li>
 *   <li><b>A pin's glow is its mailbox's depth</b> against its capacity; a drain dims it; a drop flashes ruby.</li>
 * </ul>
 *
 * <h2>Which graph</h2>
 *
 * The application's own, asked over its link when it is attached to ({@code bus graph}); else the hand-written one
 * {@code --bus.graph} names; else none, and only the framework chip. A new graph is laid out and its nodes made on the
 * thread that learned it ({@link #graph}), and swapped in by the timeline at its next frame.
 *
 * <h2>Threads</h2>
 *
 * The timeline, inside the frame, writing props and reading nothing back, but for {@link #graph} and {@link #hint}.
 * A board's electrons, pins and sparks are fixed sets of nodes made with it; nothing is made per message.
 */
final class CircuitView {

    /** Electrons that may be in flight at once. */
    static final int ELECTRONS = 384;

    /** One run from sender to mailbox, in wall time. */
    static final long TRAVEL = 700_000_000L;

    /** The board's box, in root ems; the circuit is scaled to fit it, and never drawn larger than {@link #MAX_SCALE}. */
    static final float BOARD_W = 46, BOARD_H = 21;
    private static final float MAX_SCALE = 1.15f;

    private static final float DOT_EM = 0.5f, PIN_EM = 0.62f;
    private static final long SPARK = 500_000_000L;

    private static final Color BOARD = Color.rgb(0x16201b);
    private static final Color CHIP = Color.rgb(0x22262d);
    private static final Color CHIP_EDGE = Color.rgb(0x4a515c);
    private static final Color COPPER = Color.rgb(0xb87333);
    private static final Color COPPER_DIM = Color.rgba(0.72f, 0.45f, 0.2f, 0.35f);
    private static final Color LABEL = Color.rgb(0xd5dbe3);
    private static final Color FAINT = Color.rgb(0x8a929e);
    private static final Color ELECTRON = Color.rgb(0x8fe3ff);
    private static final Color CHARGE = Color.rgb(0x8fe3ff);

    private final Gui gui;
    private final Node node;
    private final Node frame;
    private final Node hint;
    private final Node fan;
    private final Node governor;
    private final Node combo;
    private final Node comboNote;

    /** The graph shown, or about to be: compared, so asking for the same graph twice rebuilds nothing. */
    private BusGraph shown;
    /** A board laid out off the timeline, waiting for it. */
    private volatile Board next;

    // The timeline's own.
    private Board board;
    private float fanAngle;
    private long governorAt;
    private long shownBurst;
    private long comboEndedAt = -1;
    private boolean recordShown;
    private boolean comboUp;

    CircuitView(Gui gui, BusGraph graph) {
        this.gui = gui;
        Node heading = gui.text("THE BUS").textSize(Length.rem(0.8f)).textColor(gui.theme().color(Role.ACCENT));
        fan = gui.box().size(Length.rem(1.5f), Length.rem(1.5f)).hitInert(true);
        gui.onResizeUi(fan, l -> fan.picture(fanPicture(l.rect().w())));
        governor = gui.text("real time").font("mono").textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.GOVERNOR, governor);
        hint = gui.text("").textSize(Length.rem(0.7f)).textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.BUS_HINT, hint);
        combo = gui.text("").font("mono").textSize(Length.rem(1.6f)).textColor(Parts.GOLD_LIGHT)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE).width(Length.rem(12)).opacity(0f);
        comboNote = gui.text("").font("mono").textSize(Length.rem(0.7f)).textColor(Parts.RUBY_LIGHT)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE).width(Length.rem(12)).opacity(0f);
        gui.landmark(Landmarks.COMBO, combo);
        gui.landmark(Landmarks.COMBO_NOTE, comboNote);
        Node header = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(0.6f)).width(Length.FILL).children(
                heading, fan, governor, hint, gui.box().width(Length.grow(1f)),
                gui.column().alignItems(AlignItems.END).children(combo, comboNote));

        frame = gui.box().size(Length.rem(BOARD_W), Length.rem(BOARD_H)).clip(true).hitInert(true)
                .background(BOARD).corner(Length.rem(0.4f));
        gui.landmark(Landmarks.BOARD, frame);
        shown = graph;
        board = new Board(gui, graph);
        frame.append(board.layer);
        hint.text(graph.chips().isEmpty() ? "no declared bus graph: only the framework chip" : "");

        node = gui.column()
                .gap(Length.rem(0.5f))
                .padding(Length.rem(0.8f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .children(header, frame);
        gui.landmark(Landmarks.BUS, node);
    }

    Node node() {
        return node;
    }

    /** What the panel says beside the governor. Any thread. */
    void hint(String text) {
        hint.text(text);
    }

    /**
     * Show {@code graph} from the next frame, unless it is the one shown. Lays it out and makes its nodes here, on the
     * calling thread, so the timeline only swaps them in. Any thread but the timeline's.
     */
    synchronized void graph(BusGraph graph) {
        if (!graph.equals(shown)) {
            shown = graph;
            next = new Board(gui, graph);
        }
        hint.text(graph.chips().isEmpty() ? "the application declares no bus graph: only the framework chip" : "");
    }

    /** A new run: no electrons, no charge, no burst on screen. */
    void reset() {
        adopt();
        board.reset();
        shownBurst = 0;
        comboEndedAt = -1;
        comboUp = false;
        combo.opacity(0f);
        comboNote.opacity(0f);
    }

    /** A bus row has been reached by show time, at {@code now} on the wall clock. */
    void row(Row row, long now) {
        adopt();
        board.now = now;
        board.messages.row(row);
    }

    /** A board waiting for the timeline takes the old one's place. */
    private void adopt() {
        Board b = next;
        if (b != null) {
            next = null;
            board.layer.remove();
            board = b;
            frame.append(b.layer);
        }
    }

    /** Move everything for the frame at {@code now}. Returns whether anything is moving. */
    boolean frame(long now, long dt, ShowClock clock, long real, Combo burst) {
        adopt();
        boolean moving = board.frame(now);
        boolean dilated = governor(now, dt, clock, real);
        boolean counting = combo(now, real, burst);
        return moving || dilated || counting;
    }

    // ------------------------------------------------------------------ the board

    /** One graph, laid out, with its own nodes: a layer the frame holds. */
    private static final class Board implements Messages.Wire {

        final Layout layout;
        final Messages messages;
        final BeadPool pool;
        final Node layer;
        final Node[] electrons = new Node[ELECTRONS];
        final int[] carrying = new int[ELECTRONS];
        final int[] bound = new int[ELECTRONS];
        final Node[] pins;
        final Node[] sparks;
        final long[] depth;
        final long[] sparkAt;
        final int[] capacity;
        final float scale, ox, oy;
        final float[] at = new float[2];
        long now;

        Board(Gui gui, BusGraph graph) {
            layout = new Layout(graph);
            messages = new Messages(layout, this);
            long gap = TRAVEL * Math.max(1, layout.paths()) / ELECTRONS;
            pool = new BeadPool(ELECTRONS, TRAVEL, Math.max(1, layout.paths()), gap);
            scale = Math.min(MAX_SCALE, Math.min(BOARD_W / layout.width(), BOARD_H / layout.height()));
            ox = (BOARD_W - layout.width() * scale) / 2;
            oy = (BOARD_H - layout.height() * scale) / 2;
            layer = gui.box().size(Length.rem(BOARD_W), Length.rem(BOARD_H)).floatAt(Length.rem(0), Length.rem(0))
                    .hitInert(true);
            gui.onResizeUi(layer, l -> layer.picture(drawing(l.rect().w() / BOARD_W)));

            int mailboxes = layout.mailboxes();
            pins = new Node[mailboxes];
            sparks = new Node[mailboxes];
            depth = new long[mailboxes];
            sparkAt = new long[mailboxes];
            capacity = new int[mailboxes];
            for (int m = 0; m < mailboxes; m++) {
                Layout.RowBox r = layout.pin(m);
                float x = ox + r.x() * scale - PIN_EM / 2, y = oy + r.y() * scale - PIN_EM / 2;
                pins[m] = gui.box().size(Length.rem(PIN_EM), Length.rem(PIN_EM)).corner(Length.rem(0.12f))
                        .background(CHARGE).floatAt(Length.rem(x), Length.rem(y)).opacity(0.12f).hitInert(true);
                sparks[m] = gui.box().size(Length.rem(PIN_EM * 1.8f), Length.rem(PIN_EM * 1.8f))
                        .corner(Length.rem(PIN_EM * 0.9f)).background(Parts.RUBY_LIGHT)
                        .floatAt(Length.rem(x - PIN_EM * 0.4f), Length.rem(y - PIN_EM * 0.4f)).opacity(0f)
                        .hitInert(true);
                gui.landmark(Landmarks.BUS_PIN + "." + layout.mailboxName(m), pins[m]);
                layer.append(sparks[m]);
                layer.append(pins[m]);
                capacity[m] = Math.max(1, graph.mailboxes().get(m).capacity());
            }
            for (int i = 0; i < ELECTRONS; i++) {
                electrons[i] = gui.box().size(Length.rem(DOT_EM), Length.rem(DOT_EM)).corner(Length.rem(DOT_EM / 2))
                        .background(ELECTRON).floatAt(Length.rem(0), Length.rem(0)).opacity(0f).hitInert(true);
                layer.append(electrons[i]);
            }
        }

        void reset() {
            pool.clear();
            messages.reset();
            for (Node e : electrons) {
                e.opacity(0f);
            }
            for (int m = 0; m < pins.length; m++) {
                depth[m] = 0;
                sparkAt[m] = 0;
                pins[m].opacity(0.12f);
                sparks[m].opacity(0f);
            }
        }

        @Override
        public void electron(int path, int mailbox) {
            int slot = pool.spawn(path, true, now);
            carrying[slot] = path;
            bound[slot] = mailbox;
            long w = pool.weight(slot);
            if (w == 1) {
                electrons[slot].scale(1f);
            } else if ((w & (w - 1)) == 0) {
                electrons[slot].scale(1f + Math.min(1f, (63 - Long.numberOfLeadingZeros(w)) / 6f));
            }
        }

        @Override
        public void charge(int mailbox, long depth) {
            this.depth[mailbox] = depth;
            glow(mailbox);
        }

        @Override
        public void drain(int mailbox, long count) {
            depth[mailbox] = Math.max(0, depth[mailbox] - count);
            glow(mailbox);
        }

        @Override
        public void spark(int mailbox) {
            sparkAt[mailbox] = now;
            sparks[mailbox].opacity(1f);
        }

        private void glow(int mailbox) {
            pins[mailbox].opacity(0.12f + 0.88f * Math.min(1f, (float) depth[mailbox] / capacity[mailbox]));
        }

        /** Move the electrons and fade the sparks; whether anything is moving. */
        boolean frame(long now) {
            this.now = now;
            for (int i = 0; i < ELECTRONS; i++) {
                if (!pool.live(i)) {
                    continue;
                }
                double p = pool.progress(i, now);
                if (!pool.live(i)) {
                    electrons[i].opacity(0f);
                    continue;
                }
                layout.at(carrying[i], p, at);
                electrons[i].opacity(bound[i] < 0 ? (float) (1 - p) : 1f)
                        .translate(ox + at[0] * scale - DOT_EM / 2, oy + at[1] * scale - DOT_EM / 2);
            }
            boolean sparking = false;
            for (int m = 0; m < sparks.length; m++) {
                if (sparkAt[m] != 0) {
                    long since = now - sparkAt[m];
                    if (since >= SPARK) {
                        sparkAt[m] = 0;
                        sparks[m].opacity(0f);
                    } else {
                        sparks[m].opacity(1f - (float) since / SPARK);
                        sparking = true;
                    }
                }
            }
            return pool.count() > 0 || sparking;
        }

        /** The board at {@code pxPerRem}: traces under the chips, labels on them, junctions where rows meet trunks. */
        private Picture drawing(double pxPerRem) {
            double u = pxPerRem * scale;
            double x0 = ox * pxPerRem, y0 = oy * pxPerRem;
            Sketch k = new Sketch().tag("traces");
            int fw = layout.frameworkTopic();
            for (int t = 0; t < layout.topics().size(); t++) {
                Color c = t == fw ? COPPER_DIM : COPPER;
                double x = x0 + layout.trunkX(t) * u;
                k.line(x, y0 + layout.trunkTop(t) * u, x, y0 + layout.trunkBottom(t) * u, 0.12 * u, c);
            }
            for (Layout.RowBox r : layout.rows()) {
                Color c = r.topic() == fw ? COPPER_DIM : COPPER;
                double y = y0 + r.y() * u, tx = x0 + layout.trunkX(r.topic()) * u;
                k.line(x0 + r.x() * u, y, tx, y, 0.1 * u, c);
                k.circle(tx, y, 0.16 * u, c);
            }
            k.tag("chips");
            for (Layout.ChipBox c : layout.chips()) {
                double x = x0 + c.x() * u, y = y0 + c.y() * u, w = c.w() * u, h = c.h() * u;
                boolean framework = c.name().equals(BusGraph.FRAMEWORK);
                k.fill(x, y, w, h, 0.3 * u, CHIP)
                        .outline(x, y, w, h, 0.3 * u, 0.06 * u, framework ? COPPER_DIM : CHIP_EDGE);
                k.text(c.label(), x + 0.5 * u, y + 1.0 * u, 0.62 * u, framework ? FAINT : LABEL);
                String lane = framework ? "hand-wired topics" : c.lane().equals("-") ? "publisher" : "lane " + c.lane();
                k.text(lane, x + w - 0.5 * u - lane.length() * 0.27 * u, y + 1.0 * u, 0.45 * u, FAINT);
            }
            k.tag("rows");
            for (Layout.RowBox r : layout.rows()) {
                Layout.ChipBox c = layout.chips().get(r.chip());
                double y = y0 + r.y() * u;
                String label = r.pin() ? "▸ " + r.label() : r.label() + " ▸";
                k.text(label, x0 + (c.x() + 0.6) * u, y + 0.18 * u, 0.48 * u, r.pin() ? LABEL : FAINT);
            }
            return k.picture();
        }
    }

    // ------------------------------------------------------------------ the governor and the combo

    /** The governor: turns while show time is not real time, and says how far off it is. */
    private boolean governor(long now, long dt, ShowClock clock, long real) {
        long lag = clock.lag(real);
        double speed = clock.speed();
        boolean dilated = lag > 1_000_000L || Math.abs(speed - 1) > 1e-6;
        if (dilated) {
            fanAngle = (float) ((fanAngle + 360.0 * speed * dt / 1e9) % 360.0);
            fan.rotate(fanAngle);
        }
        if (now - governorAt >= 100_000_000L) {
            governorAt = now;
            governor.text(dilated ? "×" + speed(speed) + " · " + lag / 1_000_000L + " ms behind" : "real time");
        }
        return dilated;
    }

    private static String speed(double v) {
        return v >= 10 ? Long.toString(Math.round(v)) : v >= 1 ? String.valueOf(Math.round(v * 10) / 10.0)
                : String.valueOf(Math.round(v * 100) / 100.0);
    }

    /** The combo: counts a burst as it arrives, holds the total, fades. */
    private boolean combo(long now, long real, Combo burst) {
        long count = burst.count();
        boolean active = burst.active(real);
        if (burst.bursts() != shownBurst && active && count >= Combo.SHOWN) {
            shownBurst = burst.bursts();
            comboEndedAt = -1;
            recordShown = false;
            comboUp = true;
            comboNote.text("");
            comboNote.opacity(0f);
            combo.scale(1f);
        }
        if (!comboUp) {
            return false;
        }
        if (burst.newRecord() && !recordShown) {
            recordShown = true;
            comboNote.text("NEW RECORD").opacity(1f);
        }
        if (active && burst.bursts() == shownBurst) {
            combo.text(grouped(count)).opacity(1f);
            combo.scale(recordShown ? 1.25f : 1f);
            return true;
        }
        if (comboEndedAt < 0) {
            comboEndedAt = now;
            combo.text(grouped(count) + "!").scale(1f);
            if (!recordShown) {
                comboNote.text("burst · record " + grouped(burst.record())).opacity(1f);
            }
        }
        long since = now - comboEndedAt;
        if (since <= 1_200_000_000L) {
            return true;
        }
        float o = 1f - (float) Math.min(1, (double) (since - 1_200_000_000L) / 400_000_000L);
        combo.opacity(o);
        comboNote.opacity(o);
        if (o <= 0f) {
            comboUp = false;
            return false;
        }
        return true;
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
