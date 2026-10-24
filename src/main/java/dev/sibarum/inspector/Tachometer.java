package dev.sibarum.inspector;

import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * The tachometer beside the governor, drawn: a face and a needle, each a {@link Picture} in a square box centred on
 * the needle's pivot, as {@link Parts} draws the movement, so the needle is turned with {@code Node.rotate} alone.
 *
 * <h2>What it reads</h2>
 *
 * The needle is the frames the target drew a second, against the refresh of its display: at rest on zero, at the
 * last tick at vsync, and into the ruby past it, up to {@link #OVER} of vsync, for a loop drawing frames the display
 * cannot show. A loop that turns and draws nothing reads zero. Angles are clockwise from straight up, in degrees.
 * Without the target's refresh the face has no ticks and no ruby, and the view shows no needle (FN-14).
 */
final class Tachometer {

    /** Zero, low on the left. */
    static final float FROM = -120;

    /** From zero to vsync. */
    static final float SWEEP = 210;

    /** How far past vsync the needle goes before it stops: the overflow, in fractions of vsync. */
    static final double OVER = 1.15;

    private static final double RIM = 0.42, TRACK = 0.065;

    private Tachometer() {
    }

    /** The needle's angle at {@code fraction} of vsync, stopped at zero and at {@link #OVER}. */
    static float angle(double fraction) {
        double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(OVER, fraction));
        return (float) (FROM + SWEEP * f);
    }

    /** The face, {@code s} across: a track from zero to vsync, ticked at quarters when {@code scaled}, ruby past it. */
    static Picture face(double s, boolean scaled) {
        double c = s / 2;
        Sketch k = new Sketch().tag("tachometer");
        k.circle(c, c, 0.5 * s, Parts.PLATE);
        k.ring(c, c, 0.48 * s, 0.03 * s, Parts.PLATE_EDGE);
        arc(k, c, RIM * s, FROM, angle(1), TRACK * s, scaled ? Parts.BRIDGE_SHADE : Parts.PLATE_EDGE);
        if (scaled) {
            arc(k, c, RIM * s, angle(1), angle(OVER), TRACK * s, Parts.RUBY);
            for (int q = 0; q <= 4; q++) {
                double a = Math.toRadians(angle(q / 4.0));
                double r0 = (RIM - (q == 4 ? 0.17 : 0.11)) * s, r1 = (RIM + TRACK / 2) * s;
                k.line(c + Math.sin(a) * r0, c - Math.cos(a) * r0, c + Math.sin(a) * r1, c - Math.cos(a) * r1,
                        (q == 4 ? 0.05 : 0.035) * s, q == 4 ? Parts.GOLD_LIGHT : Parts.GOLD);
            }
        }
        return k.picture();
    }

    /** The needle, {@code s} across, pointing straight up from the centre: turned to read. */
    static Picture needle(double s) {
        double c = s / 2;
        return new Sketch().tag("needle")
                .line(c, c + 0.08 * s, c, c - 0.38 * s, 0.045 * s, Parts.STEEL)
                .circle(c, c, 0.07 * s, Parts.GOLD)
                .circle(c, c, 0.03 * s, Parts.RUBY)
                .picture();
    }

    /**
     * An arc about {@code (c, c)} of radius {@code r}, from {@code a0} to {@code a1} degrees clockwise from up. Not a
     * {@code polyline}: its segments are shorter than the track is thick, and a line's caps are inside its ends, so
     * it drew as beads (FN-2). Each segment is drawn half its width longer at both ends instead, as a true capsule.
     */
    private static void arc(Sketch k, double c, double r, double a0, double a1, double width,
                            dev.vexelray.canvas.Color color) {
        int n = Math.max(1, (int) Math.ceil(Math.abs(a1 - a0) / 6));
        double x0 = c + Math.sin(Math.toRadians(a0)) * r, y0 = c - Math.cos(Math.toRadians(a0)) * r;
        for (int i = 1; i <= n; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / n);
            double x1 = c + Math.sin(a) * r, y1 = c - Math.cos(a) * r;
            double len = Math.hypot(x1 - x0, y1 - y0);
            double ex = (x1 - x0) / len * width / 2, ey = (y1 - y0) / len * width / 2;
            k.line(x0 - ex, y0 - ey, x1 + ex, y1 + ey, width, color);
            x0 = x1;
            y0 = y1;
        }
    }
}
