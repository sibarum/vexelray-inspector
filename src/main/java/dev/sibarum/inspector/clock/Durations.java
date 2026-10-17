package dev.sibarum.inspector.clock;

import java.util.Locale;

/**
 * Durations as Probe writes them in {@code detail} ({@code 41.32ms}, {@code 777.4us}, {@code 850ns}, {@code 1.2s},
 * and a bare number of millis in a park budget), and back into text for a readout.
 */
public final class Durations {

    private Durations() {
    }

    /** {@code text} in nanos; -1 for text that is not a duration, {@link Long#MAX_VALUE} for {@code forever}. */
    public static long nanos(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.equals("forever")) {
            return Long.MAX_VALUE;
        }
        double unit;
        String number;
        if (t.endsWith("ns")) {
            unit = 1;
            number = t.substring(0, t.length() - 2);
        } else if (t.endsWith("us") || t.endsWith("µs")) {
            unit = 1e3;
            number = t.substring(0, t.length() - 2);
        } else if (t.endsWith("ms")) {
            unit = 1e6;
            number = t.substring(0, t.length() - 2);
        } else if (t.endsWith("s")) {
            unit = 1e9;
            number = t.substring(0, t.length() - 1);
        } else {
            unit = 1e6;             // a bare park budget is millis, as CsvView reads it
            number = t;
        }
        try {
            double v = Double.parseDouble(number.trim());
            return v < 0 ? -1 : Math.round(v * unit);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Nanos for a readout: {@code 2.39 ms}, {@code 777 µs}, {@code 1.20 s}; an em dash for an unknown (-1). */
    public static String text(long nanos) {
        if (nanos < 0) {
            return "—";
        }
        if (nanos < 1_000_000) {
            return String.format(Locale.ROOT, "%d µs", Math.round(nanos / 1e3));
        }
        if (nanos < 1_000_000_000) {
            double ms = nanos / 1e6;
            return String.format(Locale.ROOT, ms < 10 ? "%.2f ms" : ms < 100 ? "%.1f ms" : "%.0f ms", ms);
        }
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }
}
