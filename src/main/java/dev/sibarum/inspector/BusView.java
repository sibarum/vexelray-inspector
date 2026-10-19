package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.BeadPool;
import dev.sibarum.inspector.clock.BusRows;
import dev.sibarum.inspector.clock.Combo;
import dev.sibarum.inspector.clock.ShowClock;
import dev.sibarum.inspector.rows.Row;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.TextLayout;

/**
 * The bus on screen: a conveyor per topic carrying a bead per message, the mailbox each one fills, the governor that
 * says how far time is stretched, and the combo counter that says how big a burst really was.
 *
 * <h2>What moves, and what it means</h2>
 *
 * <ul>
 *   <li><b>A bead is one message</b>, published on its topic's belt the moment show time reaches it, crossing in
 *       {@link #TRAVEL} of wall time. Gold if the main thread published it, blued steel if another thread did. Beads
 *       spaced apart on a belt were apart in time; beads riding together came due together while the clock caught
 *       up.</li>
 *   <li><b>The governor turns only while time is not real</b>: slowly while a burst is stretched, fast while a debt
 *       is paid. Its plate says how fast show time runs and how far behind it is. At real time it rests.</li>
 *   <li><b>The combo counter is real time.</b> It counts a burst's messages as they arrive, however far behind the
 *       beads are, then holds the total, and says {@code NEW RECORD} when the burst beat the biggest one this run.</li>
 *   <li><b>A mailbox's fill</b>, at the end of its belt, is the depth its pumped mailbox last reported; a drain
 *       empties every one. Drops are counted in ruby.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * Everything here but {@link #hint} is called on the timeline, inside the frame, by {@link Show}: it writes props
 * and reads nothing back. Nothing here allocates per bead: the beads are a fixed set of nodes, given out by the
 * {@link BeadPool}.
 */
final class BusView {

    /** Belts: one per topic, the last shared by any further topics. */
    static final int LANES = 5;

    /** Beads that may be in flight at once: the ceiling on what one frame moves. */
    static final int BEADS = 256;

    /** One crossing, in wall time. */
    static final long TRAVEL = 900_000_000L;

    /** The least time between two beads starting on one belt: so many lanes of beads that the pool never fills. */
    static final long GAP = TRAVEL * LANES / BEADS;

    /** How long a finished burst's total stays up, then how long it takes to go. */
    private static final long HOLD = 1_200_000_000L;
    private static final long FADE = 400_000_000L;

    private static final float LABEL_EM = 11, BELT_EM = 30, HOPPER_EM = 6.5f, GAP_EM = 0.6f;
    private static final float LANE_EM = 1.35f, LANE_GAP_EM = 0.25f, BEAD_EM = 0.62f;

    private static final Color BELT = Color.rgb(0x1b1f25);
    private static final Color STEEL = Parts.BLUED;
    private static final Color GOLD = Parts.GOLD_LIGHT;

    private final Node node;
    private final Node[] lanes = new Node[LANES];
    private final Node[] labels = new Node[LANES];
    private final Node[] hoppers = new Node[LANES];
    private final String[] topics = new String[LANES];
    private final long[] depth = new long[LANES];
    private final long[] dropped = new long[LANES];
    private final Node[] beads = new Node[BEADS];
    private final Node[] gold = new Node[BEADS];
    private final Node[] steel = new Node[BEADS];
    private final Node hint;
    private final Node fan;
    private final Node governor;
    private final Node combo;
    private final Node comboNote;
    private final String[] queued = new String[65];

    // The timeline's own.
    private int used;
    private float fanAngle;
    private long governorAt;
    private long shownBurst;
    private long comboEndedAt = -1;
    private boolean recordShown;
    private boolean comboUp;
    private boolean crowded;

    BusView(Gui gui) {
        for (int i = 0; i < queued.length; i++) {
            queued[i] = i == 0 ? "" : i + " queued";
        }
        Node heading = gui.text("THE BUS").textSize(Length.rem(0.8f)).textColor(gui.theme().color(Role.ACCENT));

        fan = gui.box().size(Length.rem(1.5f), Length.rem(1.5f)).hitInert(true);
        gui.onResizeUi(fan, layout -> fan.picture(fanPicture(layout.rect().w())));
        governor = gui.text("real time").font("mono").textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.GOVERNOR, governor);

        combo = gui.text("").font("mono").textSize(Length.rem(1.6f)).textColor(GOLD)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE).width(Length.rem(12)).opacity(0f);
        comboNote = gui.text("").font("mono").textSize(Length.rem(0.7f)).textColor(Parts.RUBY_LIGHT)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE).width(Length.rem(12)).opacity(0f);
        gui.landmark(Landmarks.COMBO, combo);
        gui.landmark(Landmarks.COMBO_NOTE, comboNote);

        Node header = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(0.6f)).width(Length.FILL).children(
                heading, fan, governor, gui.box().width(Length.grow(1f)),
                gui.column().alignItems(AlignItems.END).children(combo, comboNote));

        Node column = gui.column().gap(Length.rem(LANE_GAP_EM));
        for (int i = 0; i < LANES; i++) {
            labels[i] = gui.text("").font("mono").textSize(Length.rem(0.7f))
                    .textColor(gui.theme().color(Role.INK)).width(Length.rem(LABEL_EM))
                    .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
            Node belt = gui.box().width(Length.rem(BELT_EM)).height(Length.rem(LANE_EM * 0.72f))
                    .background(BELT).border(Length.dp(1), Parts.PLATE_EDGE).corner(Length.rem(0.3f));
            hoppers[i] = gui.text("").font("mono").textSize(Length.rem(0.7f))
                    .textColor(gui.theme().color(Role.FAINT)).width(Length.rem(HOPPER_EM));
            lanes[i] = gui.row().height(Length.rem(LANE_EM)).gap(Length.rem(GAP_EM)).alignItems(AlignItems.CENTER)
                    .children(labels[i], belt, hoppers[i]);
            lanes[i].visible(false);
            gui.landmark(Landmarks.BUS_LANE + "." + (i + 1), labels[i]);
            gui.landmark(Landmarks.BUS_LANE + "." + (i + 1) + ".mailbox", hoppers[i]);
            column.append(lanes[i]);
        }
        hint = gui.text("waiting for the bus").textSize(Length.rem(0.75f)).textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.BUS_HINT, hint);
        column.append(hint);

        Node layer = gui.box()
                .size(Length.rem(BELT_EM), Length.rem(LANES * (LANE_EM + LANE_GAP_EM)))
                .floatAt(Length.rem(LABEL_EM + GAP_EM), Length.rem(0))
                .clip(true)
                .hitInert(true);
        for (int i = 0; i < BEADS; i++) {
            // Two dots, one of each colour, and the bead shows one: a colour written to a node fades to the next one
            // (the shell's PaintMotion), and a bead that changes owner must change colour at once (FN-5).
            gold[i] = dot(gui, GOLD);
            steel[i] = dot(gui, STEEL).opacity(0f);
            beads[i] = gui.box().size(Length.rem(BEAD_EM), Length.rem(BEAD_EM))
                    .floatAt(Length.rem(0), Length.rem(0)).opacity(0f).hitInert(true).children(gold[i], steel[i]);
            layer.append(beads[i]);
        }
        Node belts = gui.box().children(column, layer);

        node = gui.column()
                .width(Length.rem(LABEL_EM + BELT_EM + HOPPER_EM + 2 * GAP_EM + 1.6f))
                .gap(Length.rem(0.6f))
                .padding(Length.rem(0.8f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .children(header, belts);
        gui.landmark(Landmarks.BUS, node);
    }

    Node node() {
        return node;
    }

    /** What the panel says while it has no topic: why there is no bus yet. Any thread. */
    void hint(String text) {
        hint.text(text);
    }

    /** A new run: no topics, no beads, no burst on screen. */
    void reset(BeadPool pool) {
        for (int i = 0; i < LANES; i++) {
            topics[i] = null;
            depth[i] = 0;
            dropped[i] = 0;
            lanes[i].visible(false);
            hoppers[i].text("");
        }
        used = 0;
        crowded = false;
        hint.visible(true);
        for (Node b : beads) {
            b.opacity(0f);
        }
        pool.clear();
        shownBurst = 0;
        comboEndedAt = -1;
        comboUp = false;
        combo.opacity(0f);
        comboNote.opacity(0f);
    }

    /** A bus row has been reached by show time, at {@code now} on the wall clock. */
    void apply(Row row, BeadPool pool, long now) {
        if (BusRows.drain(row)) {
            for (int i = 0; i < used; i++) {
                if (depth[i] != 0) {
                    depth[i] = 0;
                    hopper(i);
                }
            }
            return;
        }
        if (row.kind().startsWith("pump ")) {
            return;
        }
        int lane = lane(row.kind());
        if (BusRows.publish(row)) {
            int slot = pool.spawn(lane, row.thread().equals("main"), now);
            long w = pool.weight(slot);
            if (w == 1) {
                gold[slot].opacity(pool.main(slot) ? 1f : 0f);
                steel[slot].opacity(pool.main(slot) ? 0f : 1f);
                beads[slot].scale(1f);
            } else if ((w & (w - 1)) == 0) {
                // A heavier bead is larger: twice the size at 64 messages, and no larger. Only at powers of two,
                // which is when the size changes.
                beads[slot].scale(1f + Math.min(1f, (63 - Long.numberOfLeadingZeros(w)) / 6f));
            }
        } else if (BusRows.depth(row)) {
            depth[lane] = BusRows.count(row);
            hopper(lane);
        } else if (BusRows.dropped(row)) {
            dropped[lane]++;
            hopper(lane);
        }
    }

    /** Move everything for the frame at {@code now}: beads, governor, combo. Returns whether anything is moving. */
    boolean frame(long now, long dt, ShowClock clock, long real, BeadPool pool, Combo burst) {
        for (int i = 0; i < BEADS; i++) {
            if (!pool.live(i)) {
                continue;
            }
            double p = pool.progress(i, now);
            if (!pool.live(i)) {
                beads[i].opacity(0f);
                continue;
            }
            int lane = pool.lane(i);
            beads[i].opacity(1f).translate((float) (p * (BELT_EM - BEAD_EM)),
                    lane * (LANE_EM + LANE_GAP_EM) + (LANE_EM - BEAD_EM) / 2);
        }
        boolean dilated = governor(now, dt, clock, real);
        boolean counting = combo(now, real, burst);
        return pool.count() > 0 || dilated || counting;
    }

    /** The governor: turns while show time is not real time, and says how far off it is. */
    private boolean governor(long now, long dt, ShowClock clock, long real) {
        long lag = clock.lag(real);
        double speed = clock.speed();
        boolean dilated = lag > 1_000_000L || Math.abs(speed - 1) > 1e-6;
        if (dilated) {
            // A turn a second at real speed, so a stretch of ten is a lazy tenth and catching up is a blur.
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
            // A record pops: drawn larger, settling as it climbs.
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
        if (since <= HOLD) {
            return true;
        }
        float o = 1f - (float) Math.min(1, (double) (since - HOLD) / FADE);
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

    /** The belt for {@code kind}: its topic's, given out in the order topics appear; the last is everyone else's. */
    private int lane(String kind) {
        for (int i = 0; i < used; i++) {
            String t = topics[i];
            if (kind.startsWith(t) && (kind.length() == t.length() || kind.charAt(t.length()) == ' ')) {
                return i;
            }
        }
        if (used == LANES) {
            if (!crowded) {
                crowded = true;
                labels[LANES - 1].text(topics[LANES - 1] + " and others");
            }
            return LANES - 1;
        }
        int i = used++;
        topics[i] = BusRows.topic(kind);
        labels[i].text(topics[i]);
        lanes[i].visible(true);
        hint.visible(false);
        return i;
    }

    private void hopper(int lane) {
        long d = depth[lane];
        String fill = d < queued.length ? queued[(int) d] : d + " queued";
        hoppers[lane].text(dropped[lane] == 0 ? fill : fill + (fill.isEmpty() ? "" : " · ") + dropped[lane] + " dropped");
        hoppers[lane].textColor(dropped[lane] == 0 ? Parts.STEEL : Parts.RUBY_LIGHT);
    }

    /** One bead's dot in one colour. */
    private static Node dot(Gui gui, Color color) {
        return gui.box().size(Length.rem(BEAD_EM), Length.rem(BEAD_EM)).corner(Length.rem(BEAD_EM / 2))
                .background(color).floatAt(Length.rem(0), Length.rem(0)).hitInert(true);
    }

    /** The governor's fan fly: two vanes on an arbor, as a music box's. */
    private static dev.vexelray.gui.draw.Picture fanPicture(double s) {
        double c = s / 2;
        return new Sketch().tag("fan")
                .line(c - 0.42 * s, c, c + 0.42 * s, c, 0.22 * s, Parts.STEEL)
                .circle(c, c, 0.12 * s, Parts.GOLD)
                .circle(c, c, 0.05 * s, Parts.RUBY)
                .picture();
    }
}
