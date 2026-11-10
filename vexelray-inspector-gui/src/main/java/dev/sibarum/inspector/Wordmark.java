package dev.sibarum.inspector;

import dev.vexelray.fields.sdg.SdgFile;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * The name as the title bar's logo: "Movement", C on the suite canvas's Inspector wordmark board. INSPECT and R in
 * Cinzel's engraved capitals, the O cut as the app icon's gear, at the board's title-bar cut (Bold, spaced .08 em).
 *
 * <p>The letters are not the font: they are its outlines traced once into {@code src/main/rc/wordmark.svg}, and the
 * icon plugin's pair goal makes that drawing an SDG, {@code /wordmark.sdgb}, so the word is sharp at any scale and no
 * typeface is carried for eight letters.
 *
 * <p><b>The letters follow the bar; the gear keeps its lime.</b> The letters are the drawing's layer {@code face}, in
 * the role {@code tint}, the node's own text colour, which the bar hands over as a paint ({@link TitleBar.Wordmark#ink}):
 * resolved every frame, so the pointer lifts it into the accent as it does a caption glyph. The gear is the icon's
 * #a8e05a, as the board draws it on a dark bar, and this application has only a dark theme ({@link Look}).
 */
final class Wordmark {

    private static final Log LOG = Log.of("vexelray-inspector.wordmark");

    /** The drawing's size, in its own units: a hundred to the em. */
    static final float W = 665.2f;
    static final float H = 76.71f;

    /** One unit on the screen: the board's title-bar cut, an em of 15 dp. */
    static final float DP = 0.15f;

    private Wordmark() {
    }

    /** The drawing, its letters in the role {@code tint}; null, said once, if it is not on the class path. */
    static ImageSdg face() {
        try (InputStream in = Wordmark.class.getResourceAsStream("/wordmark.sdgb")) {
            if (in == null) {
                LOG.warn("no /wordmark.sdgb on the class path (the icon plugin's pair goal makes it); no wordmark");
                return null;
            }
            return ImageSdg.of(SdgFile.read(in.readAllBytes()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("the wordmark /wordmark.sdgb could not be read; no wordmark", e);
            return null;
        }
    }

    /**
     * The wordmark for the title bar's lockup: the drawing, its letters lit in whatever ink the bar hands it. The ink is
     * the node's text colour, a paint the renderer resolves in each frame's theme and fades as it fades text.
     */
    static TitleBar.Wordmark bar(Gui gui) {
        Node node = gui.box().role("wordmark").size(Length.dp(W * DP), Length.dp(H * DP)).textColor(Role.INK);
        ImageSdg face = face();
        if (face != null) {
            node.sdg(face);
        }
        return new TitleBar.Wordmark() {
            @Override
            public Node node() {
                return node;
            }

            @Override
            public void ink(Paint ink) {
                node.textColor(ink);
            }
        };
    }
}
