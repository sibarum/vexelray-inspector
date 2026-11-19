package dev.sibarum.inspector.linux;

import dev.sibarum.inspector.core.ProcessMeters.Sample;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The files read, as text; and, where there is a /proc, this test's own process. */
class LinuxProcessMetersTest {

    @Test
    void procFilesAreRead() {
        assertEquals(5_012_345_678L, LinuxProcessMeters.schedstat("5012345678 123456 789\n"));
        assertEquals(-1L, LinuxProcessMeters.schedstat(""));
        String status = "Name:\tjava\nVmPeak:\t  900000 kB\nVmRSS:\t  217088 kB\nThreads:\t40\n";
        assertEquals(217_088L * 1024, LinuxProcessMeters.kib(status, "VmRSS:"));
        assertEquals(-1L, LinuxProcessMeters.kib(status, "VmSwap:"));
    }

    @Test
    void thisProcessIsRead() {
        long pid = ProcessHandle.current().pid();
        assumeTrue(Files.isRegularFile(Path.of("/proc", Long.toString(pid), "schedstat")),
                "only where this process is in /proc");
        try (var reader = new LinuxProcessMeters().open(pid)) {
            Sample s = reader.read().orElseThrow();
            assertTrue(s.cpuNanos() > 0 && s.residentBytes() > 10L << 20, s.toString());
            assertTrue(s.memoryTotalBytes() > s.memoryAvailableBytes() && s.memoryAvailableBytes() > 0, s.toString());
        }
    }
}
