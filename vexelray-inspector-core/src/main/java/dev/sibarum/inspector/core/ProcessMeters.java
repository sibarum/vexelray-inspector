package dev.sibarum.inspector.core;

import java.util.Optional;

/**
 * What reads a process from outside, by its pid, on this platform: answered by an OS module
 * ({@code vexelray-inspector-windows}, {@code vexelray-inspector-linux}), found through {@code ServiceLoader} and put on
 * the class path by {@code -gui}'s OS-activated profile, as the stack's own platform modules are. Shared code never
 * asks which OS it is on ({@code vexelray-build}'s {@code isolate} goal); {@link ProcessMeter} asks this.
 */
public interface ProcessMeters {

    /**
     * A reader of process {@code pid}, held open for as long as it is read.
     *
     * @throws IllegalStateException saying why, when the process cannot be opened
     */
    Reader open(long pid);

    /** One process, read again and again. */
    interface Reader extends AutoCloseable {

        /** A reading now; empty when the process has gone or could not be read this time. */
        Optional<Sample> read();

        @Override
        void close();
    }

    /**
     * One reading. Sizes in bytes; -1 for what was not read.
     *
     * @param cpuNanos             CPU time the process has used since it started, on every core
     * @param residentBytes        how much of it is in RAM: its working set, its RSS
     * @param memoryTotalBytes     the machine's RAM
     * @param memoryAvailableBytes the machine's RAM free for a program to use
     * @param cores                the cores the machine has
     */
    record Sample(long cpuNanos, long residentBytes, long memoryTotalBytes, long memoryAvailableBytes, int cores) {

        /** As a row's detail: each figure its unit in its key. */
        public String detail() {
            return "cpu_ns=" + cpuNanos + " rss_bytes=" + residentBytes + " mem_total_bytes=" + memoryTotalBytes
                    + " mem_avail_bytes=" + memoryAvailableBytes + " cores=" + cores;
        }

        /** The reading a row's detail says; empty when it is not one. */
        public static Optional<Sample> of(String detail) {
            long cpu = field(detail, "cpu_ns=");
            if (cpu < 0) {
                return Optional.empty();
            }
            return Optional.of(new Sample(cpu, field(detail, "rss_bytes="), field(detail, "mem_total_bytes="),
                    field(detail, "mem_avail_bytes="), (int) field(detail, "cores=")));
        }

        private static long field(String detail, String key) {
            int at = detail.indexOf(key);
            if (at < 0) {
                return -1L;
            }
            int from = at + key.length();
            int to = detail.indexOf(' ', from);
            try {
                return Long.parseLong(detail.substring(from, to < 0 ? detail.length() : to));
            } catch (NumberFormatException notANumber) {
                return -1L;
            }
        }
    }
}
