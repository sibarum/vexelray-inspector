package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The two looks, dark and light, as anchors: the application's own, and the person chooses between them in the app
 * menu (Theme, then Light or Dark). The framework remembers the choice and re-themes every window with it.
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
 *
 * <h2>The light look is authored, not reflected</h2>
 *
 * <p>The Escapement design is dark only; the light look is the same family carried onto a near-white page: the same
 * hues, every lightness chosen again. A mirror of the dark page (L 0.128) would land at a grey where a light page
 * wants 0.975; the cyan and the brass, which glow on near-black, have to come down until they read on white as text
 * does; a shadow that is depth on a dark page is a smudge on a light one, so its alpha drops by two thirds; and the
 * edge light that glints on a dark card smears on a white one. The framework derives neither look, on purpose.
 *
 * <h2>Write the role, never the colour</h2>
 *
 * <p>The person can change the look while the application runs. A node that holds a {@link Role} is repainted in the
 * new look, and fades to it; a node that was handed {@code gui.theme().color(role)} keeps the colour it was given,
 * forever. So nodes are handed roles, and what is worked out from the theme once (the lanes' and the graph's inks,
 * which are blended and thinned per mark) is worked out again when the look shifts.
 */
final class Look {

    // ---------------------------------------------------------------- the dark look

    /** The page: the colour behind everything, and what the frame clears to. {@code #05070B}. */
    private static final Oklab DARK_PAGE = Oklab.polar(0.1277, 0.0108, -100.36);

    /** Primary text, a cold near-white. {@code #D6E2EC}. */
    private static final Oklab DARK_INK = Oklab.polar(0.9067, 0.0188, -117.02);

    /** Cyan: what the run did -- a lane's marks, a lit arrow, a hover wash, a focus ring. {@code #45C8DC}. */
    private static final Oklab DARK_ACCENT = Oklab.polar(0.7703, 0.1145, -150.42);

    /** The fill of a filled control: the cyan, dark enough that its label is the ink. */
    private static final Oklab DARK_ACTION = Oklab.polar(0.4800, 0.0820, -150.42);

    /** Red, at the chroma that turns the framework's caution into the design's brass. */
    private static final Oklab DARK_DANGER = Oklab.polar(0.6200, 0.1510, 22.00);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DARK_DEPTH = Oklab.polar(0.0800, 0.0080, -100.36);

    /** How far one surface is from the next. Two steps from the page is a card. */
    private static final double DARK_STEP = 0.0174;

    /** How fast the ink fades towards the page: text(1) is DIM, text(2) is FAINT. */
    private static final double DARK_FADE = 0.163;

    /** How dark a shadow is. */
    private static final double DARK_SHADOW_ALPHA = 0.60;

    /** The dark palette: the Escapement design's. */
    static final Palette DARK_PALETTE = new Palette(DARK_PAGE, DARK_STEP, DARK_INK, DARK_FADE, DARK_ACCENT,
            DARK_ACTION, DARK_DANGER, DARK_DEPTH, DARK_SHADOW_ALPHA);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint. Letterpress is the opposite call: it buys
     * contrast for white-on-fill labels by spending crispness, which small text can least afford.
     */
    static final Theme DARK = Theme.of(DARK_PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ---------------------------------------------------------------- the light look

    /** A near-white page at the dark page's blue, so the two read as one family. */
    private static final Oklab LIGHT_PAGE = Oklab.polar(0.9750, 0.0060, -100.36);

    /** Primary text: a deep blue slate rather than black, at the ink's own hue. */
    private static final Oklab LIGHT_INK = Oklab.polar(0.2500, 0.0250, -117.02);

    /** The cyan, brought down from 0.77 so a lane's marks and a focus ring still read on white. */
    private static final Oklab LIGHT_ACCENT = Oklab.polar(0.5200, 0.0950, -150.42);

    /** A filled control's fill: a little deeper than the accent, so its white label has room. */
    private static final Oklab LIGHT_ACTION = Oklab.polar(0.4800, 0.0900, -150.42);

    /**
     * Red, darker for the same reason as the cyan, at a chroma that still turns the framework's caution (made at the
     * accent's lightness) into a brass that reads on white.
     */
    private static final Oklab LIGHT_DANGER = Oklab.polar(0.5300, 0.1500, 22.00);

    /** Shadows, at the page's hue. */
    private static final Oklab LIGHT_DEPTH = Oklab.polar(0.2000, 0.0100, -100.36);

    /** Each surface a step down from the page: wider than the dark look's, which would not show on white. */
    private static final double LIGHT_STEP = 0.0220;

    /** The ink fades faster than on the dark page, so dim and faint stay apart and faint still reads. */
    private static final double LIGHT_FADE = 0.260;

    /** A shadow a third as dark as the dark look's: here depth is a hint, not a void. */
    private static final double LIGHT_SHADOW_ALPHA = 0.18;

    /** The light palette. */
    static final Palette LIGHT_PALETTE = new Palette(LIGHT_PAGE, LIGHT_STEP, LIGHT_INK, LIGHT_FADE, LIGHT_ACCENT,
            LIGHT_ACTION, LIGHT_DANGER, LIGHT_DEPTH, LIGHT_SHADOW_ALPHA);

    /** No edge light, which on a white card reads as a smear, and no letterpress. */
    static final Theme LIGHT = Theme.of(LIGHT_PALETTE, Shading.ON_LIGHT, Relief.STANDARD, false, false);

    // ---------------------------------------------------------------- roles of our own

    /** A card's edge on the dark page, {@code #172230}: brighter than the ladder's line. */
    private static final Color DARK_EDGE = Oklab.polar(0.2486, 0.0310, -105.21).toColor();

    /** A card's edge on the light page: darker than the ladder's line, at the same hue. */
    private static final Color LIGHT_EDGE = Oklab.polar(0.7900, 0.0300, -105.21).toColor();

    /** A card's edge: further from the page than the ladder's line, so a card reads against the page. */
    static final Role EDGE = p -> p.equals(LIGHT_PALETTE) ? LIGHT_EDGE : DARK_EDGE;

    /** Brass on the dark page, {@code #E0AA4A}. */
    private static final Color DARK_CURSOR = Oklab.polar(0.7706, 0.1283, 79.07).toColor();

    /** Brass on the light page: the same hue, darkened until it reads on white as text does. */
    private static final Color LIGHT_CURSOR = Oklab.polar(0.5300, 0.1100, 79.07).toColor();

    /**
     * Brass: where the person is looking -- the cursor, the lane the arrows step through, the row the cursor is on.
     */
    static final Role CURSOR = p -> p.equals(LIGHT_PALETTE) ? LIGHT_CURSOR : DARK_CURSOR;

    /** Pale brass on the dark page, {@code #F0D29C}. */
    private static final Color DARK_PATH = Oklab.polar(0.8763, 0.0774, 81.97).toColor();

    /**
     * The path on the light page: as on the dark one, the cursor's brass taken further from the page and quieter in
     * chroma, which on white is darker and browner.
     */
    private static final Color LIGHT_PATH = Oklab.polar(0.4500, 0.0650, 81.97).toColor();

    /**
     * The rest of the path of the event the cursor is on, in every panel. The cursor's own colour, further from the page
     * and quieter, so the path reads as the cursor's without being mistaken for it.
     */
    static final Role PATH = p -> p.equals(LIGHT_PALETTE) ? LIGHT_PATH : DARK_PATH;

    /**
     * A draw held for the GPU, on the lanes: the GPU being slow, a frame the run could not show when it was ready. It was
     * the caution colour, which this palette makes the cursor's brass, so a held draw read as where the person is
     * looking; the danger's red is its own hue, and as strong.
     */
    static final Role HELD_FOR_GPU = Role.DANGER;

    private Look() {
    }
}
