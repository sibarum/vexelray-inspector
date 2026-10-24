package dev.sibarum.inspector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphViewTest {

    @Test
    void aRateAFrameIsWrittenToTwoFiguresAndAQuietOneIsADash() {
        assertEquals("12/f", GraphView.perFrame(720, 12.3));
        assertEquals("2.5/f", GraphView.perFrame(150, 2.5));
        assertEquals("0.07/f", GraphView.perFrame(4, 0.0667));
        assertEquals("—", GraphView.perFrame(0.01, 3), "a flow gone quiet");
        assertEquals("—", GraphView.perFrame(5, Double.NaN), "before the first frame");
    }

    @Test
    void theFrameRateIsWholeAtTenAndAboveAndADashBeforeAnyFrame() {
        assertEquals("of 144 Hz", GraphView.vsync(6_944_000));
        assertEquals("vsync not reported (FN-14)", GraphView.vsync(-1));
        assertEquals("60 fps", GraphView.fps(59.8));
        assertEquals("2.5 fps", GraphView.fps(2.46));
        assertEquals("0 fps", GraphView.fps(0.01));
        assertEquals("— fps", GraphView.fps(0));
    }

    @Test
    void theNeedleRestsOnZeroReachesTheLastTickAtVsyncAndStopsShortlyPastIt() {
        assertEquals(Tachometer.FROM, Tachometer.angle(0));
        assertEquals(Tachometer.FROM, Tachometer.angle(Double.NaN));
        assertEquals(Tachometer.FROM + Tachometer.SWEEP, Tachometer.angle(1), 1e-4);
        assertEquals(Tachometer.angle(Tachometer.OVER), Tachometer.angle(3), "pinned past the overflow");
        assertTrue(Tachometer.angle(1.05) > Tachometer.angle(1), "a little past vsync shows");
    }
}
