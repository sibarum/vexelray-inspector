package dev.sibarum.inspector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the graph window's graph is looked at: zoomed about the pointer, moved, and always covering the stage. */
class GraphLensTest {

    private static final float W = 800f;
    private static final float H = 600f;

    @Test
    void aZoomKeepsThePointUnderThePointerUnderIt() {
        GraphLens l = GraphLens.FITTED.zoomed(2f, 200f, 150f, W, H);
        assertEquals(2f, l.zoom());
        // The graph's point at (200, 150) at zoom one is at (400, 300) of the graph laid out at two: shown at 200, 150.
        assertEquals(200f, 400f + l.x(), 1e-3f);
        assertEquals(150f, 300f + l.y(), 1e-3f);
        GraphLens again = l.zoomed(1.5f, 500f, 100f, W, H);
        float gx = (500f - l.x()) / l.zoom();
        assertEquals(500f, gx * again.zoom() + again.x(), 1e-3f, "zoomed again about another point");
    }

    @Test
    void itNeverZoomsOutPastFittingNorInPastTheMost() {
        assertEquals(GraphLens.FITTED, GraphLens.FITTED.zoomed(0.5f, 10f, 10f, W, H), "fitted is as far out as it goes");
        assertEquals(1f, GraphLens.FITTED.wheel(-3f, 400f, 300f, W, H).zoom());
        assertEquals(GraphLens.MAX_ZOOM, GraphLens.FITTED.zoomed(1000f, 400f, 300f, W, H).zoom());
        assertEquals(GraphLens.NOTCH, GraphLens.FITTED.wheel(1f, 0f, 0f, W, H).zoom(), 1e-5f, "a notch zooms by one");
    }

    @Test
    void aMoveStopsWhereTheGraphsEdgeMeetsTheStages() {
        GraphLens l = GraphLens.FITTED.zoomed(2f, 0f, 0f, W, H);
        assertEquals(new GraphLens(2f, 0f, 0f), l.moved(50f, 50f, W, H), "past the top left: held there");
        assertEquals(new GraphLens(2f, -W, -H), l.moved(-5000f, -5000f, W, H), "past the bottom right: held there");
        assertEquals(new GraphLens(2f, -100f, -40f), l.moved(-100f, -40f, W, H));
        assertEquals(GraphLens.FITTED, GraphLens.FITTED.moved(30f, -30f, W, H), "fitted, nothing to move");
    }

    @Test
    void zoomingOutPullsTheGraphBackOverTheStage() {
        GraphLens far = new GraphLens(4f, -3 * W, -3 * H);
        GraphLens out = far.zoomed(0.5f, 0f, 0f, W, H);
        assertEquals(2f, out.zoom());
        assertEquals(-W, out.x(), 1e-3f, "its right edge back at the stage's");
        assertEquals(-H, out.y(), 1e-3f);
    }
}
