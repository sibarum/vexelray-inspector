package dev.sibarum.inspector.windows;

import dev.sibarum.inspector.core.ProcessMeters;
import dev.sibarum.inspector.core.ProcessMeters.Sample;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real calls, on this test's own process: there is nothing to mock in a handle. */
class WindowsProcessMetersTest {

    @Test
    void itIsWhatServiceLoaderFinds() {
        assertEquals(WindowsProcessMeters.class,
                ServiceLoader.load(ProcessMeters.class).findFirst().orElseThrow().getClass());
    }

    @Test
    void thisProcessIsReadAndItsCpuOnlyGrows() {
        try (ProcessMeters.Reader reader = new WindowsProcessMeters().open(ProcessHandle.current().pid())) {
            Sample first = reader.read().orElseThrow();
            long spin = System.nanoTime() + 100_000_000L;
            long n = 0;
            while (System.nanoTime() < spin) {
                n += Long.numberOfTrailingZeros(n + 1);   // a tenth of a second of CPU, past a scheduler tick or six
            }
            Sample second = reader.read().orElseThrow();
            assertTrue(second.cpuNanos() > first.cpuNanos(), "spun " + n + ": " + first + " then " + second);
            assertTrue(second.residentBytes() > 10L << 20, "a JVM is in RAM: " + second);
            assertTrue(second.memoryTotalBytes() > second.memoryAvailableBytes() && second.memoryAvailableBytes() > 0,
                    second.toString());
            assertEquals(Runtime.getRuntime().availableProcessors(), second.cores());
        }
    }

    @Test
    void aProcessThatIsNotThereIsRefusedSayingSo() {
        // The highest pid a DWORD that is a multiple of four can be: nothing runs under it.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WindowsProcessMeters().open(0xFFFF_FFFCL));
        assertTrue(e.getMessage().contains("refused"), e.getMessage());
    }
}
