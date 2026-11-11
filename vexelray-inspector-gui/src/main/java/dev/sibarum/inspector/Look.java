package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The palette, as anchors.
 *
 * <h2>A palette is a construction, not a list of colours</h2>
 *
 * <p>{@link Palette} takes a handful of anchors and derives the rest: surfaces are an even ladder from the page
 * at a fixed lightness step, and text is the ink blended towards the page at a fixed rate. That is the point --
 * every surface in the application stays in step with every other one for free, and a design change is a
 * number here rather than fifteen hex codes spread across the tree.
 *
 * <h2>The design these are measured from</h2>
 *
 * <p>The "Escapement" redesign (2026-11-10): a near-black blue page, cyan for what the run did and brass for where
 * the person is looking. Its colours, measured in Oklab, are fewer decisions than they look:
 *
 * <ul>
 *   <li>page {@code #05070B}, chrome {@code #070A0F} and card {@code #0A0E14} are one ladder at a step of about
 *       0.017 (a card is two steps up, the framework's {@link Role#PANEL});</li>
 *   <li>ink {@code #D6E2EC}, dim {@code #A9BACA} and faint {@code #8597A8} are one fade, of about 0.163;</li>
 *   <li>cyan {@code #45C8DC} is the accent, and brass {@code #E0AA4A} lands within a few degrees of the
 *       framework's caution, which is derived from the danger's chroma: so the danger is set to the chroma that
 *       puts it there, and a warning is brass for free.</li>
 * </ul>
 *
 * <p>What does not fit the construction is declared below as roles of our own, at their measured values: the card's
 * edge, which the design draws brighter than the ladder's line, and the two brasses, which are the cursor's rather
 * than a warning's. {@code LookTest} pins each authored colour against what is drawn, so a gap is measured, never
 * absorbed.
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** The page: the colour behind everything, and what the frame clears to. {@code #05070B}. */
    private static final Oklab PAGE = Oklab.polar(0.1277, 0.0108, -100.36);

    /** Primary text, a cold near-white. {@code #D6E2EC}. */
    private static final Oklab INK = Oklab.polar(0.9067, 0.0188, -117.02);

    /** Cyan: what the run did -- a lane's marks, a lit arrow, a hover wash, a focus ring. {@code #45C8DC}. */
    private static final Oklab ACCENT = Oklab.polar(0.7703, 0.1145, -150.42);

    /** The fill of a filled control: the cyan, dark enough that its label is the ink. */
    private static final Oklab ACTION = Oklab.polar(0.4800, 0.0820, -150.42);

    /** Red, at the chroma that turns the framework's caution into the design's brass. */
    private static final Oklab DANGER = Oklab.polar(0.6200, 0.1510, 22.00);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DEPTH = Oklab.polar(0.0800, 0.0080, -100.36);

    /** How far one surface is from the next. Two steps from the page is a card. */
    private static final double STEP = 0.0174;

    /** How fast the ink fades towards the page: text(1) is DIM, text(2) is FAINT. */
    private static final double FADE = 0.163;

    /** How dark a shadow is. */
    private static final double SHADOW_ALPHA = 0.60;

    static final Palette PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint. Letterpress is the opposite call: it buys
     * contrast for white-on-fill labels by spending crispness, which small text can least afford.
     */
    static final Theme THEME = Theme.of(PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ---------------------------------------------------------------- roles of our own

    /** A card's edge, {@code #172230}: brighter than the ladder's line, so a card reads against the page. */
    static final Role EDGE = fixed(Oklab.polar(0.2486, 0.0310, -105.21));

    /**
     * Brass, {@code #E0AA4A}: where the person is looking -- the cursor, the lane the arrows step through, the row
     * the cursor is on.
     */
    static final Role CURSOR = fixed(Oklab.polar(0.7706, 0.1283, 79.07));

    /**
     * Pale brass, {@code #F0D29C}: the rest of the path of the event the cursor is on, in every panel. The cursor's
     * own colour, lighter and quieter, so the path reads as the cursor's without being mistaken for it.
     */
    static final Role PATH = fixed(Oklab.polar(0.8763, 0.0774, 81.97));

    /** A role that is {@code colour} whatever the palette. */
    private static Role fixed(Oklab colour) {
        Color c = colour.toColor();
        return p -> c;
    }

    private Look() {
    }
}
