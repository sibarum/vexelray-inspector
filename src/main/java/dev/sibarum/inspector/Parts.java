package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * The escapement's parts, drawn: the plate, the balance wheel, the pallet fork, the escape wheel and the balance
 * cock, each as a {@link Picture} in its own box.
 *
 * <h2>Why pictures on turning nodes</h2>
 *
 * <p>Every part that moves only turns about one pivot, and a node can be drawn turned about the centre of its box
 * ({@code Node.rotate}) for the price of one prop: nothing reflows and nothing is measured. So each moving part is a
 * picture authored once, upright, in a box centred on its pivot, and the animation writes nothing but an angle.
 * A picture is only rebuilt when its box is resized. A marched SDF viewport (drawing.md §7) would have bought
 * lighting and depth this does not need yet, at the cost of a technique dependency and a pipeline.
 *
 * <h2>Geometry</h2>
 *
 * <p>All in fractions of the movement's side {@code s}, with y down. {@link #BALANCE}, {@link #FORK} and
 * {@link #ESCAPE} are the pivots, and the {@code *_BOX} sizes are the boxes that hold each part, centred on its
 * pivot; {@link Movement} places the boxes from the same numbers, so the two cannot disagree.
 */
final class Parts {

    static final double BALANCE_X = 0.38, BALANCE_Y = 0.40;
    static final double FORK_X = 0.615, FORK_Y = 0.585;
    static final double ESCAPE_X = 0.765, ESCAPE_Y = 0.745;

    static final double BALANCE = 0.25;          // rim radius
    static final double ESCAPE = 0.115;          // tooth-tip radius
    static final double PALLET_TIP = 0.165;      // from the fork's pivot to each pallet stone's working end

    static final double BALANCE_BOX = 0.60;
    static final double FORK_BOX = 0.54;
    static final double ESCAPE_BOX = 0.26;

    /** From the fork's pivot to the balance's, and to the escape wheel's: the lever and the pallets point so. */
    private static final double TO_BALANCE = Math.atan2(BALANCE_Y - FORK_Y, BALANCE_X - FORK_X);
    private static final double TO_ESCAPE = Math.atan2(ESCAPE_Y - FORK_Y, ESCAPE_X - FORK_X);

    // Metals and stones. Fixed rather than themed: a movement is gold, steel and ruby in any theme.
    static final Color PLATE = Color.rgb(0x2a2f37);
    static final Color PLATE_STRIPE = Color.rgba(1f, 1f, 1f, 0.022f);
    static final Color PLATE_EDGE = Color.rgb(0x4a515c);
    static final Color BRIDGE = Color.rgb(0x9aa3af);
    static final Color BRIDGE_LIGHT = Color.rgb(0xd5dbe3);
    static final Color BRIDGE_SHADE = Color.rgb(0x5d6672);
    static final Color GOLD = Color.rgb(0xc9a257);
    static final Color GOLD_LIGHT = Color.rgb(0xf0d79a);
    static final Color GOLD_SHADE = Color.rgb(0x7d5f27);
    static final Color STEEL = Color.rgb(0xb9c1cc);
    static final Color STEEL_SHADE = Color.rgb(0x66707d);
    static final Color BLUED = Color.rgb(0x4f78c4);
    static final Color RUBY = Color.rgb(0xb3203f);
    static final Color RUBY_LIGHT = Color.rgb(0xf0778c);
    static final Color ENGRAVING = Color.rgba(1f, 1f, 1f, 0.22f);

    private Parts() {
    }

    /** The plate under everything, at side {@code s} px: a disc with Geneva stripes and an engraving. */
    static Picture plate(double s) {
        double c = s / 2, r = 0.49 * s;
        Sketch k = new Sketch().tag("plate");
        k.circle(c, c, r, PLATE_EDGE).circle(c, c, r - 0.012 * s, PLATE);
        // Côtes de Genève: wide, faint diagonal bands, each a chord of the disc.
        k.tag("stripes");
        double inner = r - 0.03 * s, band = 0.055 * s;
        for (double d = -inner + band / 2; d < inner; d += band * 1.6) {
            double half = Math.sqrt(inner * inner - d * d) - band / 2;
            if (half <= 0) {
                continue;
            }
            // The chord at distance d along the normal (1,1)/√2, running along (1,-1)/√2.
            double mx = c + d * 0.7071, my = c + d * 0.7071;
            k.line(mx - half * 0.7071, my + half * 0.7071, mx + half * 0.7071, my - half * 0.7071, band,
                    PLATE_STRIPE);
        }
        k.tag("sinks");
        jewel(k, FORK_X * s, FORK_Y * s, 0.03 * s);
        jewel(k, ESCAPE_X * s, ESCAPE_Y * s, 0.03 * s);
        k.tag("engraving")
                .text("MAIN LOOP", 0.70 * s, 0.30 * s, 0.03 * s, ENGRAVING)
                .text("ESCAPEMENT", 0.70 * s, 0.34 * s, 0.022 * s, ENGRAVING);
        return k.picture();
    }

    /** The balance wheel, upright, in a box {@code box} px square centred on its pivot. */
    static Picture balance(double box) {
        double s = box / BALANCE_BOX, c = box / 2, r = BALANCE * s;
        Sketch k = new Sketch();
        // Rim: a gold band, lit on its inner edge.
        k.tag("rim").ring(c, c, r, 0.038 * s, GOLD_SHADE).ring(c, c, r - 0.004 * s, 0.03 * s, GOLD)
                .ring(c, c, r - 0.032 * s, 0.005 * s, GOLD_LIGHT);
        // Three arms.
        k.tag("arms");
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(-90 + 120 * i);
            k.line(c, c, c + Math.cos(a) * (r - 0.02 * s), c + Math.sin(a) * (r - 0.02 * s), 0.024 * s, GOLD);
        }
        // Timing screws round the rim, each with a slot: the turning is visible in every one of them.
        k.tag("screws");
        for (int i = 0; i < 16; i++) {
            if (i % 4 == 2) {
                continue;               // the gaps a real rim leaves for poising
            }
            double a = Math.toRadians(i * 22.5 + 11.25);
            double x = c + Math.cos(a) * (r + 0.012 * s), y = c + Math.sin(a) * (r + 0.012 * s);
            double sr = 0.016 * s;
            k.circle(x, y, sr, GOLD_SHADE).circle(x, y, sr * 0.8, GOLD_LIGHT);
            k.line(x - Math.cos(a) * sr * 0.6, y - Math.sin(a) * sr * 0.6, x + Math.cos(a) * sr * 0.6,
                    y + Math.sin(a) * sr * 0.6, sr * 0.28, GOLD_SHADE);
        }
        // The hairspring: an Archimedean spiral of blued steel from the collet out.
        k.tag("hairspring");
        int n = 220;
        double[] xs = new double[n], ys = new double[n];
        double r0 = 0.04 * s, r1 = 0.175 * s, turns = 7.5;
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            double a = t * turns * 2 * Math.PI;
            double rr = r0 + (r1 - r0) * t;
            xs[i] = c + Math.cos(a) * rr;
            ys[i] = c + Math.sin(a) * rr;
        }
        k.polyline(xs, ys, Math.max(1, 0.0045 * s), BLUED);
        // The roller and its impulse jewel, pointing at the fork when the wheel is at rest (angle 0).
        double toFork = TO_BALANCE + Math.PI;
        k.tag("roller").circle(c, c, 0.05 * s, STEEL_SHADE).circle(c, c, 0.042 * s, STEEL)
                .circle(c + Math.cos(toFork) * 0.05 * s, c + Math.sin(toFork) * 0.05 * s, 0.012 * s, RUBY);
        return k.picture();
    }

    /** The pallet fork, upright, in a box {@code box} px square centred on its pivot. */
    static Picture fork(double box) {
        double s = box / FORK_BOX, c = box / 2;
        Sketch k = new Sketch().tag("lever");
        double reach = 0.235 * s;
        double tx = c + Math.cos(TO_BALANCE) * reach, ty = c + Math.sin(TO_BALANCE) * reach;
        k.line(c, c, tx, ty, 0.024 * s, STEEL_SHADE).line(c, c, tx, ty, 0.016 * s, STEEL);
        // The horns at the fork's mouth, either side of where the impulse jewel enters.
        double px = -Math.sin(TO_BALANCE), py = Math.cos(TO_BALANCE);
        for (int side = -1; side <= 1; side += 2) {
            double hx = tx + px * side * 0.02 * s, hy = ty + py * side * 0.02 * s;
            k.line(hx, hy, hx + Math.cos(TO_BALANCE) * 0.03 * s, hy + Math.sin(TO_BALANCE) * 0.03 * s,
                    0.012 * s, STEEL);
        }
        // The pallets: two arms straddling the escape wheel, each tipped with a ruby stone whose end lies on the
        // circle of the teeth' tips, which is where the law of cosines puts it for a tip PALLET_TIP from the pivot.
        k.tag("pallets");
        double between = Math.hypot(ESCAPE_X - FORK_X, ESCAPE_Y - FORK_Y);
        double spread = Math.acos((PALLET_TIP * PALLET_TIP + between * between - ESCAPE * ESCAPE)
                / (2 * PALLET_TIP * between));
        for (int side = -1; side <= 1; side += 2) {
            double a = TO_ESCAPE + side * (spread + 0.12);
            double ax = c + Math.cos(a) * 0.12 * s, ay = c + Math.sin(a) * 0.12 * s;
            k.line(c, c, ax, ay, 0.024 * s, STEEL_SHADE).line(c, c, ax, ay, 0.016 * s, STEEL);
            double t = TO_ESCAPE + side * spread;
            double sx = c + Math.cos(t) * PALLET_TIP * s, sy = c + Math.sin(t) * PALLET_TIP * s;
            k.line(ax, ay, sx, sy, 0.016 * s, RUBY).line(ax, ay, sx, sy, 0.005 * s, RUBY_LIGHT);
        }
        k.tag("pivot");
        jewel(k, c, c, 0.024 * s);
        return k.picture();
    }

    /** The escape wheel, upright, in a box {@code box} px square centred on its pivot: twenty club teeth. */
    static Picture escape(double box) {
        double s = box / ESCAPE_BOX, c = box / 2, rim = 0.082 * s, tip = ESCAPE * s;
        Sketch k = new Sketch().tag("teeth");
        for (int i = 0; i < 20; i++) {
            double a = Math.toRadians(i * 18);
            double b = a + Math.toRadians(9);
            k.line(c + Math.cos(a) * rim, c + Math.sin(a) * rim, c + Math.cos(b) * tip, c + Math.sin(b) * tip,
                    0.011 * s, STEEL);
        }
        k.tag("rim").ring(c, c, rim + 0.004 * s, 0.016 * s, STEEL_SHADE).ring(c, c, rim, 0.01 * s, STEEL);
        k.tag("spokes");
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i);
            k.line(c, c, c + Math.cos(a) * rim, c + Math.sin(a) * rim, 0.012 * s, STEEL);
        }
        k.tag("pivot");
        jewel(k, c, c, 0.02 * s);
        return k.picture();
    }

    /**
     * The balance cock, at side {@code s} px: the bridge that holds the balance's top pivot, drawn over the wheel.
     * It and the plate do not move, so it is the movement's overlay.
     */
    static Picture cock(double s) {
        double bx = BALANCE_X * s, by = BALANCE_Y * s;
        double away = Math.atan2(BALANCE_Y - 0.5, BALANCE_X - 0.5);          // out from the plate's centre
        double fx = bx + Math.cos(away) * 0.27 * s, fy = by + Math.sin(away) * 0.27 * s;
        Sketch k = new Sketch().tag("cock");
        // The arm tapers from its foot to the pivot: lines all starting at the foot, each longer and narrower than
        // the last. Not short segments end to end: a canvas line's caps lie inside its ends, so a segment shorter
        // than it is thick draws as a bead (framework-notes FN-2).
        double[] reach = {0.45, 0.6, 0.75, 0.9, 1.0};
        for (int pass = 0; pass < 2; pass++) {
            Color color = pass == 0 ? BRIDGE_SHADE : BRIDGE;
            double inset = pass == 0 ? 0 : 0.008 * s;
            k.circle(fx, fy, 0.072 * s - inset, color);
            for (int i = 0; i < reach.length; i++) {
                double t = reach[i];
                double w = (0.11 - 0.05 * i / (reach.length - 1)) * s - inset * 1.5;
                double ex = fx + (bx - fx) * t, ey = fy + (by - fy) * t;
                k.line(fx, fy, ex + (bx - fx) / Math.hypot(bx - fx, by - fy) * w / 2,
                        ey + (by - fy) / Math.hypot(bx - fx, by - fy) * w / 2, w, color);
            }
            k.circle(bx, by, 0.048 * s - inset, color);
        }
        // A polished bevel along the top edge.
        double px = Math.sin(away), py = -Math.cos(away);
        k.line(fx + px * 0.035 * s, fy + py * 0.035 * s, bx + px * 0.026 * s, by + py * 0.026 * s, 0.004 * s,
                Color.withAlpha(BRIDGE_LIGHT, 0.6f));
        // The screw that holds it down.
        double sr = 0.03 * s;
        k.tag("screw").circle(fx, fy, sr, BRIDGE_SHADE).circle(fx, fy, sr * 0.85, STEEL)
                .line(fx - sr * 0.6, fy + sr * 0.6, fx + sr * 0.6, fy - sr * 0.6, sr * 0.25, STEEL_SHADE);
        // The regulator's index, across the arm, with its fast and slow marks either side of its tip.
        double ix = bx - px * 0.15 * s, iy = by - py * 0.15 * s;
        double ux = Math.cos(away) * 0.035 * s, uy = Math.sin(away) * 0.035 * s;
        k.tag("index").line(bx, by, ix, iy, 0.009 * s, STEEL_SHADE).line(bx, by, ix, iy, 0.005 * s, STEEL)
                .text("+", ix + ux - 0.009 * s, iy + uy + 0.01 * s, 0.03 * s, ENGRAVING)
                .text("−", ix - ux - 0.009 * s, iy - uy + 0.01 * s, 0.03 * s, ENGRAVING);
        k.tag("jewel");
        jewel(k, bx, by, 0.026 * s);
        return k.picture();
    }

    /** A ruby in a gold setting: the chaton, the stone, its hole and a glint. */
    private static void jewel(Sketch k, double x, double y, double r) {
        k.circle(x, y, r, GOLD_SHADE).circle(x, y, r * 0.86, GOLD).circle(x, y, r * 0.6, RUBY)
                .circle(x - r * 0.18, y - r * 0.18, r * 0.2, RUBY_LIGHT).circle(x, y, r * 0.12, GOLD_SHADE);
    }
}
