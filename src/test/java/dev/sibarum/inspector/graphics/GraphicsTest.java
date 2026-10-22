package dev.sibarum.inspector.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicsTest {

    private static final int API_1_3_280 = (1 << 22) | (3 << 12) | 280;

    @Test
    void aDiscreteGpuThroughDxgi() {
        long nvidia = (566L << 22) | (36L << 14);
        Graphics g = Graphics.parse("ok gpu device\n"
                + "device=NVIDIA GeForce RTX 4070\ntype=2\nvendor=0x10de\ndriver=" + nvidia + "\napi=" + API_1_3_280
                + "\npresent=dxgi\nvalidation=off\n");
        assertTrue(g.known());
        assertFalse(g.software());
        assertEquals("GPU · NVIDIA GeForce RTX 4070 · DXGI", g.summary());
        assertEquals("discrete GPU", g.kind());
        assertEquals("NVIDIA (0x10de)", g.vendor());
        assertEquals("566.36.0.0", g.driver());
        assertEquals("1.3.280", g.vulkan());
        assertEquals("off", g.validation());
    }

    @Test
    void llvmpipeIsSoftwareOnASwapchain() {
        Graphics g = Graphics.parse("ok gpu device\r\n"
                + "device=llvmpipe (LLVM 17.0.6, 256 bits)\r\ntype=4\r\nvendor=65541\r\n"
                + "driver.name=llvmpipe\r\ndriver.info=Mesa 24.0.5\r\napi=" + API_1_3_280
                + "\r\npresent=swapchain\r\npresent.mode=fifo\r\nimages=3\r\n");
        assertTrue(g.software());
        assertEquals("SOFTWARE · llvmpipe (LLVM 17.0.6, 256 bits) · swapchain · fifo · 3 images", g.summary());
        assertEquals("CPU (software)", g.kind());
        assertEquals("Mesa (0x10005)", g.vendor());
        assertEquals("llvmpipe Mesa 24.0.5", g.driver());
    }

    @Test
    void aSoftwareAdapterIsKnownByNameWhateverTypeItClaims() {
        Graphics g = Graphics.parse("ok gpu device\ndevice=Microsoft Direct3D12 (Microsoft Basic Render Driver)\ntype=1");
        assertTrue(g.software());
        assertEquals("integrated GPU (software)", g.kind());
        assertEquals("—", g.present());
    }

    @Test
    void driverVersionsAreDecodedTheVendorsWay() {
        // Intel's Windows driver: 18.14 bits.
        long intel = (101L << 14) | 5333;
        assertEquals("101.5333", Graphics.parse("ok\nvendor=32902\ndriver=" + intel).driver());
        // The same vendor under Mesa uses Vulkan's packing.
        assertEquals("Intel open-source Mesa driver 24.0.5", Graphics.parse(
                "ok\nvendor=32902\ndriver.name=Intel open-source Mesa driver\ndriver=" + ((24 << 22) | 5)).driver());
        assertEquals("1.3.280", Graphics.parse("ok\nvendor=4098\ndriver=" + API_1_3_280).driver());
    }

    @Test
    void aRefusalIsTheReason() {
        Graphics g = Graphics.parse("err not available yet: gpu needs presenter statistics from GuiApp");
        assertFalse(g.known());
        assertFalse(g.none());
        assertEquals("not available yet: gpu needs presenter statistics from GuiApp", g.reason());
        assertEquals("graphics not reported", g.summary());
        assertEquals("—", g.device());
    }

    @Test
    void missingAndUnknownFactsAreDashesAndIgnored() {
        Graphics g = Graphics.parse("ok gpu device\ndevice=X\nfuture.key=1\nnot a fact\ntype=nine");
        assertEquals("GPU · X", g.summary());
        assertEquals("—", g.kind());
        assertEquals("—", g.vendor());
        assertEquals("—", g.driver());
        assertEquals("—", g.vulkan());
    }

    @Test
    void nothingAttachedSaysNothing() {
        assertEquals("", Graphics.NONE.summary());
        assertTrue(Graphics.NONE.none());
        assertFalse(Graphics.RECORDED.none());
    }
}
