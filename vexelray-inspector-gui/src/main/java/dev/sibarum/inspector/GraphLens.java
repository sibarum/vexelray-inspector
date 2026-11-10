package dev.sibarum.inspector;

/**
 * How the graph window's graph is looked at: how far it is zoomed, and where its top left corner is on the stage. A
 * value, replaced at each gesture, and pure, so a test reads it.
 *
 * <p>Zoomed, the graph is laid out over a larger area, {@code zoom} times the stage each way, rather than drawn
 * larger: the parts spread apart, their names get the room the panel cut them to, and the text stays its own size.
 * So zoom never goes below one, where the graph fits the stage, and the graph always covers the stage:
 * {@code x} is between {@code stageW - stageW * zoom} and nought, and {@code y} likewise.
 *
 * @param zoom how many times the stage the graph is laid out over, each way, at least one
 * @param x    where the graph's left edge is, from the stage's, px: never positive
 * @param y    where its top edge is, from the stage's, px: never positive
 */
record GraphLens(float zoom, float x, float y) {

    /** The whole graph in the stage. */
    static final GraphLens FITTED = new GraphLens(1f, 0f, 0f);

    /** How far one wheel notch zooms. */
    static final float NOTCH = 1.25f;

    /** The farthest it zooms: at this the graph is laid out over twelve stages each way. */
    static final float MAX_ZOOM = 12f;

    /**
     * Zoomed by {@code factor} about {@code (px, py)}, the pointer from the stage's top left: the point of the graph
     * under it stays under it, unless that would leave the stage showing past the graph's edge.
     */
    GraphLens zoomed(float factor, float px, float py, float stageW, float stageH) {
        if (!(factor > 0f) || factor == 1f) {
            return this;
        }
        float z = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float k = z / zoom;
        return new GraphLens(z, px - (px - x) * k, py - (py - y) * k).held(stageW, stageH);
    }

    /** The wheel turned {@code notches}, positive zooming in, with the pointer at {@code (px, py)}. */
    GraphLens wheel(float notches, float px, float py, float stageW, float stageH) {
        return zoomed((float) Math.pow(NOTCH, notches), px, py, stageW, stageH);
    }

    /** Moved by {@code (dx, dy)} px, as far as the graph's edges let it go. */
    GraphLens moved(float dx, float dy, float stageW, float stageH) {
        return new GraphLens(zoom, x + dx, y + dy).held(stageW, stageH);
    }

    /** Kept covering a stage {@code stageW} by {@code stageH}: see the class comment. */
    GraphLens held(float stageW, float stageH) {
        float hx = clamp(x, stageW - stageW * zoom, 0f);
        float hy = clamp(y, stageH - stageH * zoom, 0f);
        return hx == x && hy == y ? this : new GraphLens(zoom, hx, hy);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(Math.min(lo, hi), Math.min(hi, v));
    }
}
