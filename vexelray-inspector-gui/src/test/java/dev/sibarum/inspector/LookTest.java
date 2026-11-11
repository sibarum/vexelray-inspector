package dev.sibarum.inspector;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette, held to what a dark theme has to be true of.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>When this application gets a real design, the useful version of this test is the one that <b>pins the gap
 * between the design and the construction</b> -- assert each authored colour against what the palette derives,
 * with the difference written down. A gap that is measured is a known quantity; a gap that is absorbed is a
 * surprise waiting for a review.
 */
class LookTest {

    @Test
    void theInkReadsAgainstThePage() {
        Color page = Look.THEME.color(Role.PAGE);
        Color ink = Look.THEME.color(Role.INK);
        assertTrue(luminance(ink) - luminance(page) > 0.5f,
                "primary text should be far lighter than the page it sits on");
    }

    @Test
    void surfacesClimbAwayFromThePage() {
        float page = luminance(Look.THEME.color(Role.PAGE));
        float panel = luminance(Look.THEME.color(Role.PANEL));
        float raised = luminance(Look.THEME.color(Role.RAISED));
        assertTrue(panel > page, "a panel should sit above the page");
        assertTrue(raised > panel, "a raised surface should sit above a panel");
    }

    @Test
    void textFadesInSteps() {
        float ink = luminance(Look.THEME.color(Role.INK));
        float dim = luminance(Look.THEME.color(Role.DIM));
        float faint = luminance(Look.THEME.color(Role.FAINT));
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @Test
    void theAccentIsNotTheInk() {
        assertNotEquals(Look.THEME.color(Role.INK), Look.THEME.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /**
     * The design's authored colours against what the palette draws, a role each: the gap is pinned, so a moved anchor
     * that drifts from the design fails here rather than in a review. 0.01 in Oklab is under a just-noticeable step.
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

    private static void near(int rgb, Role role, double within) {
        dev.vexelray.gui.core.style.Oklab want = dev.vexelray.gui.core.style.Oklab.of(Color.rgb(rgb));
        dev.vexelray.gui.core.style.Oklab got = dev.vexelray.gui.core.style.Oklab.of(Look.THEME.color(role));
        double d = Math.sqrt(Math.pow(want.l() - got.l(), 2) + Math.pow(want.a() - got.a(), 2)
                + Math.pow(want.b() - got.b(), 2));
        assertTrue(d <= within, String.format("#%06X drawn %.4f away in Oklab, more than %.3f", rgb, d, within));
    }

    /** Rough perceived brightness -- enough to order two shades, which is all these assertions need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
