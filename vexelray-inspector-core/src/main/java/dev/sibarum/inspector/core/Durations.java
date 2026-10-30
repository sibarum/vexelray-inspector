package dev.sibarum.inspector.core;

/**
 * A probe row's duration, as its detail writes one ({@code 51.68ms}, {@code 539.3us}, {@code 820ns}, {@code 1.2s}),
 * read back into nanoseconds. A pure function, so a step may call it.
 */
final class Durations {

    private Durations() {
    }

    /** {@code detail}'s duration in nanoseconds, or -1 when it is not one. */
    static long nanos(String detail) {
        String d = detail.strip();
        long unit;
        int digits;
        if (d.endsWith("ns")) {
            unit = 1L;
            digits = d.length() - 2;
        } else if (d.endsWith("us") || d.endsWith("µs")) {
            unit = 1_000L;
            digits = d.length() - 2;
        } else if (d.endsWith("ms")) {
            unit = 1_000_000L;
            digits = d.length() - 2;
        } else if (d.endsWith("s")) {
            unit = 1_000_000_000L;
            digits = d.length() - 1;
        } else {
            return -1;
        }
        try {
            double value = Double.parseDouble(d.substring(0, digits));
            return value < 0 ? -1 : Math.round(value * unit);
        } catch (NumberFormatException notADuration) {
            return -1;
        }
    }
}
