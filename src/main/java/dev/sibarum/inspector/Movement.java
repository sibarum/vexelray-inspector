package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.Beat;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.text.TextLayout;
import java.util.function.DoubleFunction;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

/**
 * The escapement on screen: a plate, the balance wheel, the pallet fork and the escape wheel, with the balance cock
 * over them and a plaque that says what the loop is doing.
 *
 * <h2>What moves, and what it means</h2>
 *
 * <ul>
 *   <li><b>One beat per frame.</b> Each {@code frame.present} swings the balance from one side to the other over
 *       {@link #BEAT}; the fork snaps across and the escape wheel steps half a tooth in the first third of it, which
 *       is where a real escapement gives its impulse. Frames closer together than a beat interrupt the swing and
 *       turn it round, so a burst of frames reads as a shudder, which is what it is.</li>
 *   <li><b>A park is a rest.</b> Between beats the wheel stands where its last swing left it, and the plaque names
 *       the park's budget ({@code 200ms}, {@code forever}).</li>
 * </ul>
 *
 * <p>Nothing else moves. There is no idle sway: a wheel that moved while the loop was parked would be lying.
 *
 * <h2>Threads</h2>
 *
 * <p>{@link #tick} and {@link #show} are called on the source's thread. The swing itself runs on the timeline
 * ({@code KronoGui.onTimeline}), so the angles are only ever touched there, and each ramp writes nothing but one
 * rotation per part per frame.
 */
final class Movement {

    /** The movement's side, in root ems. Every part is placed in fractions of it ({@link Parts}). */
    static final float SIDE = 30;

    /** One swing of the balance. */
    static final Dur BEAT = Dur.ms(130);

    /** How far the balance swings either side of rest, in degrees: a healthy watch's amplitude. */
    static final float AMPLITUDE = 140;

    /** How far the fork throws either side of its line, in degrees. */
    static final float FORK_THROW = 9;

    /** One beat moves the escape wheel half a tooth of its twenty. */
    static final float ESCAPE_STEP = 9;

    private final KronoGui krono;
    private final Node node;
    private final Node balance;
    private final Node fork;
    private final Node escape;
    private final Node plaque;
    private final Color resting;
    private final Color ticking;

    // The timeline's own.
    private int side = 1;
    private int generation;
    private float angle;
    private float forkAngle;
    private float escapeAngle;

    Movement(Gui gui, KronoGui krono) {
        this.krono = krono;
        this.resting = Parts.GOLD_LIGHT;
        this.ticking = gui.theme().color(Role.DIM);

        balance = part(gui, Parts.BALANCE_X, Parts.BALANCE_Y, Parts.BALANCE_BOX, Parts::balance);
        fork = part(gui, Parts.FORK_X, Parts.FORK_Y, Parts.FORK_BOX, Parts::fork);
        escape = part(gui, Parts.ESCAPE_X, Parts.ESCAPE_Y, Parts.ESCAPE_BOX, Parts::escape);

        plaque = gui.text("WAITING FOR ROWS")
                .font("mono")
                .textSize(Length.rem(0.85f))
                .textColor(ticking)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .width(Length.rem(0.42f * SIDE))
                .padding(Length.rem(0.35f))
                .background(Color.rgb(0x1b1f25))
                .border(Length.dp(1), Parts.PLATE_EDGE)
                .corner(Length.rem(0.3f))
                .floatAt(Length.rem(0.17f * SIDE), Length.rem(0.80f * SIDE))
                .hitInert(true);
        gui.landmark(Landmarks.PLAQUE, plaque);

        node = gui.box()
                .size(Length.rem(SIDE), Length.rem(SIDE))
                .children(escape, fork, balance, plaque);
        gui.landmark(Landmarks.MOVEMENT, node);
        gui.landmark(Landmarks.BALANCE, balance);
        gui.onResizeUi(node, layout -> {
            float s = layout.rect().w();
            node.picture(Parts.plate(s));
            node.overlay(Parts.cock(s));
        });
    }

    /** A turning part: a square box centred on its pivot, holding its picture upright. */
    private static Node part(Gui gui, double x, double y, double box, DoubleFunction<dev.vexelray.gui.draw.Picture> draw) {
        Node n = gui.box()
                .size(Length.rem((float) (box * SIDE)), Length.rem((float) (box * SIDE)))
                .floatAt(Length.rem((float) ((x - box / 2) * SIDE)), Length.rem((float) ((y - box / 2) * SIDE)))
                .hitInert(true);
        gui.onResizeUi(n, layout -> n.picture(draw.apply(layout.rect().w())));
        return n;
    }

    Node node() {
        return node;
    }

    /** A frame was presented: one beat. */
    void tick() {
        krono.onTimeline(this::swing);
    }

    /** Say what the loop is doing on the plaque. */
    void show(Beat beat) {
        if (beat.frames() == 0 && !beat.resting()) {
            plaque.text("WAITING FOR A FRAME").textColor(ticking).border(Length.dp(1), Parts.PLATE_EDGE);
        } else if (beat.resting() && !beat.restsNotAtAll()) {
            plaque.text(beat.restsForever() ? "AT REST · UNTIL WOKEN" : "AT REST · " + beat.budget())
                    .textColor(resting).border(Length.dp(1), Parts.GOLD_SHADE);
        } else {
            plaque.text("TICK · #" + (beat.frame() >= 0 ? beat.frame() : beat.frames())).textColor(ticking)
                    .border(Length.dp(1), Parts.PLATE_EDGE);
        }
    }

    /** On the timeline: swing to the other side, interrupting a swing still under way. */
    private void swing() {
        side = -side;
        int g = ++generation;
        float from = angle, to = side * AMPLITUDE;
        float forkFrom = forkAngle, forkTo = -side * FORK_THROW;
        float escapeFrom = escapeAngle, escapeTo = (float) Math.ceil(escapeAngle / ESCAPE_STEP + 0.001) * ESCAPE_STEP;
        krono.ramp(BEAT, Ease.IN_OUT_SINE, p -> {
            if (g != generation) {
                return;                 // a newer beat has taken over
            }
            float impulse = (float) Math.min(1, p / 0.3);
            angle = from + (to - from) * (float) p;
            forkAngle = forkFrom + (forkTo - forkFrom) * impulse;
            escapeAngle = (escapeFrom + (escapeTo - escapeFrom) * impulse) % 360f;
            balance.rotate(angle);
            fork.rotate(forkAngle);
            escape.rotate(escapeAngle);
        }, () -> { });
    }
}
