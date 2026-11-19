package dev.sibarum.inspector.linux;

import dev.sibarum.inspector.core.ProcessMeters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A process read on Linux, from {@code /proc}: {@code /proc/<pid>/schedstat}, whose first field is the nanoseconds it
 * has spent on a CPU (exact, unlike {@code stat}'s clock ticks); {@code /proc/<pid>/status}'s {@code VmRSS}; and
 * {@code /proc/meminfo}'s {@code MemTotal} and {@code MemAvailable}.
 */
public final class LinuxProcessMeters implements ProcessMeters {

    public LinuxProcessMeters() {
    }

    @Override
    public Reader open(long pid) {
        Path dir = Path.of("/proc", Long.toString(pid));
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("no process " + pid);
        }
        return new Proc(dir);
    }

    private record Proc(Path dir) implements Reader {

        @Override
        public Optional<Sample> read() {
            try {
                long cpu = schedstat(Files.readString(dir.resolve("schedstat")));
                if (cpu < 0) {
                    return Optional.empty();
                }
                String meminfo = Files.readString(Path.of("/proc/meminfo"));
                return Optional.of(new Sample(cpu, kib(Files.readString(dir.resolve("status")), "VmRSS:"),
                        kib(meminfo, "MemTotal:"), kib(meminfo, "MemAvailable:"),
                        Runtime.getRuntime().availableProcessors()));
            } catch (IOException | RuntimeException gone) {
                return Optional.empty();
            }
        }

        @Override
        public void close() {
        }
    }

    /** {@code /proc/<pid>/schedstat}'s first field, nanoseconds on a CPU; -1 when it says none. */
    static long schedstat(String text) {
        String[] fields = text.strip().split("\\s+");
        try {
            return Long.parseLong(fields[0]);
        } catch (RuntimeException unreadable) {
            return -1L;
        }
    }

    /** The {@code key}'s line of a {@code /proc} file, given in kB, in bytes; -1 when it has none. */
    static long kib(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key)) {
                String[] parts = line.substring(key.length()).strip().split("\\s+");
                try {
                    return Long.parseLong(parts[0]) * 1024L;
                } catch (RuntimeException unreadable) {
                    return -1L;
                }
            }
        }
        return -1L;
    }
}
