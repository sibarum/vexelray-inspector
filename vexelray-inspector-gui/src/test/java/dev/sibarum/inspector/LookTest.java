package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both looks, each held to what a look has to be true of, whichever way its page faces; and the dark one pinned to the
 * design it is measured from.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Every check is written as a <b>distance from the page</b>, not as "lighter" or "darker", so one test holds the
 * dark look and the light one alike. Contrast is WCAG's ratio: 4.5 for body text, 3 for what only has to be seen.
 */
class LookTest {

    static Stream<Theme> looks() {
        return Stream.of(Look.DARK, Look.LIGHT);
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theInkReadsAgainstThePage(Theme look) {
        assertReads(look, Role.INK, 7.0, "primary text");
        assertReads(look, Role.DIM, 4.5, "dim text");
        assertReads(look, Role.FAINT, 3.0, "faint text");
    }

    /**
     * The colours text is drawn in besides the ink, the application's own roles among them: the row the cursor is on is
     * brass, the rest of its path pale brass, a warning caution, the lanes' marks cyan.
     */
    @ParameterizedTest
    @MethodSource("looks")
    void everyTextColourOfOurOwnReadsAgainstThePage(Theme look) {
        assertReads(look, Look.CURSOR, 4.5, "the cursor's row");
        assertReads(look, Look.PATH, 4.5, "the rest of the cursor's path");
        assertReads(look, Role.CAUTION, 4.5, "a warning");
        assertReads(look, Role.ACCENT, 3.0, "the accent, a lane's marks and a lit arrow");
    }

    /** A card's edge stands further off the page than the ladder's line, in both looks: that is why it is its own. */
    @ParameterizedTest
    @MethodSource("looks")
    void aCardsEdgeIsFurtherFromThePageThanTheLine(Theme look) {
        double edge = contrast(look.color(Look.EDGE), look.color(Role.PAGE));
        double line = contrast(look.color(Role.LINE), look.color(Role.PAGE));
        assertTrue(edge > line, "a card's edge is " + String.format("%.2f", edge) + ":1, the line "
                + String.format("%.2f", line) + ":1");
        assertTrue(contrast(look.color(Look.EDGE), look.color(Role.PANEL)) >= 1.15,
                "a card's edge shows against the card, as much as the design's dark one does");
    }

    /** A draw held for the GPU is not the cursor's brass, nor the marks' cyan, and shows on the lanes' panel. */
    @ParameterizedTest
    @MethodSource("looks")
    void aDrawHeldForTheGpuHasAColourOfItsOwn(Theme look) {
        Oklab held = Oklab.of(look.color(Look.HELD_FOR_GPU));
        for (Role other : List.of(Look.CURSOR, Look.PATH, Role.ACCENT)) {
            double apart = Math.abs(((held.hueDegrees() - Oklab.of(look.color(other)).hueDegrees()) % 360 + 540)
                    % 360 - 180);
            assertTrue(apart >= 30.0, "held for the GPU is " + Math.round(apart)
                    + " degrees from another of the lanes' colours");
        }
        assertTrue(contrast(look.color(Look.HELD_FOR_GPU), look.color(Role.PANEL)) >= 3.0, "seen on the lanes");
    }

    /** The path reads as the cursor's and is not mistaken for it. */
    @ParameterizedTest
    @MethodSource("looks")
    void thePathIsTheCursorsAndNotTheCursor(Theme look) {
        Oklab cursor = Oklab.of(look.color(Look.CURSOR));
        Oklab path = Oklab.of(look.color(Look.PATH));
        assertTrue(Math.abs(cursor.l() - path.l()) >= 0.08, "the path is a step off the cursor's lightness");
        assertTrue(path.chroma() < cursor.chroma(), "the path is quieter than the cursor");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void surfacesStepAwayFromThePage(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float panel = Math.abs(luminance(look.color(Role.PANEL)) - page);
        float raised = Math.abs(luminance(look.color(Role.RAISED)) - page);
        assertTrue(panel > 0, "a panel should stand off the page");
        assertTrue(raised > panel, "a raised surface should stand further off the page than a panel");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void textFadesInSteps(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = Math.abs(luminance(look.color(Role.INK)) - page);
        float dim = Math.abs(luminance(look.color(Role.DIM)) - page);
        float faint = Math.abs(luminance(look.color(Role.FAINT)) - page);
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theAccentIsNotTheInk(Theme look) {
        assertNotEquals(look.color(Role.INK), look.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /** The two looks are two: an application role that answered the same in both would not follow a choice. */
    @ParameterizedTest
    @MethodSource("looks")
    void theLooksFaceOppositeWays(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = luminance(look.color(Role.INK));
        assertTrue(look == Look.DARK ? ink > page : ink < page, "the dark look's ink is above its page, the light one's below");
    }

    @Test
    void everyRoleOfOurOwnHasAnAnswerInEachLook() {
        assertNotEquals(Look.DARK.color(Look.EDGE), Look.LIGHT.color(Look.EDGE), "a card's edge");
        assertNotEquals(Look.DARK.color(Look.CURSOR), Look.LIGHT.color(Look.CURSOR), "the cursor");
        assertNotEquals(Look.DARK.color(Look.PATH), Look.LIGHT.color(Look.PATH), "the path");
    }

    /** The light look carries the dark one's hues: one family, not a second design. */
    @Test
    void theLightLookKeepsTheDarkOnesHues() {
        sameHue(Role.ACCENT, 3.0);
        sameHue(Look.CURSOR, 3.0);
        sameHue(Look.PATH, 3.0);
    }

    /**
     * The design's authored colours against what the dark palette draws, a role each: the gap is pinned, so a moved
     * anchor that drifts from the design fails here rather than in a review. 0.01 in Oklab is under a just-noticeable
     * step.
     */
    @Test
    void theDesignsColoursAreWhatIsDrawn() {
        near(0x05070B, Role.PAGE, 0.004);
        near(0x070A0F, Role.CHROME, 0.004);
        near(0x0A0E14, Role.PANEL, 0.004);
        near(0xD6E2EC, Role.INK, 0.004);
        // The gap the construction leaves: the design's greys are bluer (chroma about 0.03) than a blend of the ink
        // and the page can be (about 0.017), at the right lightness. Pinned rather than absorbed.
        near(0xA9BACA, Role.DIM, 0.015);
        near(0x8597A8, Role.FAINT, 0.02);
        near(0x45C8DC, Role.ACCENT, 0.004);
        near(0x172230, Look.EDGE, 0.004);
        near(0xE0AA4A, Look.CURSOR, 0.004);
        near(0xF0D29C, Look.PATH, 0.004);
        // The framework derives caution; the danger's chroma is chosen so it lands near the brass, at its own hue.
        near(0xE0AA4A, Role.CAUTION, 0.03);
    }

    private static void sameHue(Role role, double withinDegrees) {
        double dark = Oklab.of(Look.DARK.color(role)).hueDegrees();
        double light = Oklab.of(Look.LIGHT.color(role)).hueDegrees();
        double d = Math.abs(((dark - light) % 360 + 540) % 360 - 180);
        assertEquals(0.0, d, withinDegrees, "the light look's hue is the dark one's");
    }

    private static void near(int rgb, Role role, double within) {
        Oklab want = Oklab.of(Color.rgb(rgb));
        Oklab got = Oklab.of(Look.DARK.color(role));
        double d = Math.sqrt(Math.pow(want.l() - got.l(), 2) + Math.pow(want.a() - got.a(), 2)
                + Math.pow(want.b() - got.b(), 2));
        assertTrue(d <= within, String.format("#%06X drawn %.4f away in Oklab, more than %.3f", rgb, d, within));
    }

    private static void assertReads(Theme look, Role role, double least, String what) {
        double ratio = contrast(look.color(role), look.color(Role.PAGE));
        assertTrue(ratio >= least, what + " is " + String.format("%.2f", ratio) + ":1 against the page in the "
                + (look == Look.DARK ? "dark" : "light") + " look; it needs " + least + ":1");
    }

    /** WCAG's contrast ratio between two colours. */
    private static double contrast(Color a, Color b) {
        double la = relative(a);
        double lb = relative(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance, from sRGB. */
    private static double relative(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** Rough perceived brightness -- enough to order two shades, which is all the ordering checks need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
