package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a pass's own rows say it did, gathered while they arrive and said on the pass's event as two fields: where its
 * time went ({@link Model#SPENT}) and what its GUI changes did ({@link Model#CHANGES}). Without it each of those rows is
 * shown bare, and a {@code pump drain} or a {@code mutation unchanged} says nothing of which pass, window or change it
 * was. Replaced, never changed.
 *
 * <p>The rows, all on {@code main} and inside the pass's {@code frame} zone, so all written before the zone's own row
 * (checked against a run's rows, 2026-11-21):
 *
 * <ul>
 *   <li>{@code before frame}: the application's edge, its input pump and clocks.</li>
 *   <li>{@code tree update}: the update, which holds the rest: the {@code dispatch input}, the GUI's change pump
 *       ({@code pump drain}), {@code flex layout}, {@code resolve geometry}, {@code scroll shift}.</li>
 *   <li>{@code <topic> batch <n>}: a mailbox the drain emptied, {@code vexelray.gui.<window>.mutations} for a window's
 *       changes. It names what {@code pump drain} delivered to, which the drain's own row does not.</li>
 *   <li>{@code mutation <outcome>}: what one change did when applied (vexelray-gui's {@code Reconciler}).</li>
 * </ul>
 *
 * @param before    nanoseconds in {@code before frame}
 * @param update    nanoseconds in {@code tree update}
 * @param input     nanoseconds in {@code dispatch input}, inside the update
 * @param layout    nanoseconds laying out, inside the update
 * @param drained   nanoseconds in the drains that delivered something, inside the update
 * @param windows   how many changes each window's mailbox delivered, by window
 * @param outcomes  how many applied changes had each outcome, by its words
 */
record PassParts(long before, long update, long input, long layout, long drained, Map<String, Long> windows,
                 Map<String, Long> outcomes) {

    static final PassParts NONE = new PassParts(0L, 0L, 0L, 0L, 0L, Map.of(), Map.of());

    private static final String MUTATIONS = ".mutations batch";
    private static final String GUI = "vexelray.gui.";

    PassParts {
        windows = Map.copyOf(windows);
        outcomes = Map.copyOf(outcomes);
    }

    /** Whether {@code r} is one of a pass's own rows that this gathers; see the class comment. */
    static boolean isPart(Row r) {
        if (!r.thread().equals("main") || r.frame() <= 0) {
            return false;
        }
        String kind = r.kind();
        return switch (r.lane()) {
            case "frame" -> kind.equals("before frame");
            case "layout" -> kind.equals("tree update") || kind.equals("dispatch input") || isLayout(kind)
                    || kind.startsWith("mutation ");
            case "bus" -> kind.equals("pump drain") || kind.endsWith(MUTATIONS);
            default -> false;
        };
    }

    private static boolean isLayout(String kind) {
        return kind.startsWith("flex layout") || kind.equals("resolve geometry") || kind.equals("scroll shift");
    }

    /** These with {@code r} counted, which {@link #isPart} said is one. */
    PassParts plus(Row r) {
        String kind = r.kind();
        if (kind.startsWith("mutation ")) {
            return new PassParts(before, update, input, layout, drained, windows,
                    add(outcomes, outcome(kind.substring("mutation ".length())), 1L));
        }
        if (kind.endsWith(MUTATIONS)) {
            long n = count(r.detail());
            String topic = kind.substring(0, kind.length() - MUTATIONS.length());
            String window = topic.startsWith(GUI) ? topic.substring(GUI.length()) : topic;
            return n <= 0 ? this : new PassParts(before, update, input, layout, drained, add(windows, window, n),
                    outcomes);
        }
        long took = Math.max(0L, Durations.nanos(r.detail()));
        return switch (kind) {
            case "before frame" -> new PassParts(before + took, update, input, layout, drained, windows, outcomes);
            case "tree update" -> new PassParts(before, update + took, input, layout, drained, windows, outcomes);
            case "dispatch input" -> new PassParts(before, update, input + took, layout, drained, windows, outcomes);
            // An empty drain, every pass's input pump's, is the time to look and find nothing: not where it went.
            case "pump drain" -> new PassParts(before, update, input, layout, drained + took, windows, outcomes);
            default -> new PassParts(before, update, input, layout + took, drained, windows, outcomes);
        };
    }

    /** The fields these fill of their pass, in {@link Model#FIELDS}' order; none when there is nothing to say. */
    List<Field> fields() {
        List<Field> out = new ArrayList<>(2);
        List<String> spent = new ArrayList<>();
        if (update > 0) {
            List<String> inside = new ArrayList<>();
            if (drained > 0 && !windows.isEmpty()) {
                inside.add("draining its queues " + ms(drained));
            }
            if (layout > 0) {
                inside.add("layout " + ms(layout));
            }
            if (input > 0) {
                inside.add("input to widgets " + ms(input));
            }
            spent.add("updating the tree " + ms(update) + (inside.isEmpty() ? "" : " (" + String.join(", ", inside) + ")"));
        }
        if (before > 0) {
            spent.add("input and clocks " + ms(before));
        }
        if (!spent.isEmpty()) {
            out.add(new Field(Model.SPENT, String.join(", ", spent)));
        }
        long applied = outcomes.values().stream().mapToLong(Long::longValue).sum();
        if (applied > 0 || !windows.isEmpty()) {
            out.add(new Field(Model.CHANGES, changes(applied)));
        }
        return out;
    }

    /**
     * {@code 30 to the main window: 22 did nothing, 6 relaid it out, 2 redrew it}, or with more than one window,
     * {@code 34 to windows events 4, main 30: ...}.
     */
    private String changes(long applied) {
        StringBuilder sb = new StringBuilder();
        long delivered = windows.values().stream().mapToLong(Long::longValue).sum();
        sb.append(Math.max(applied, delivered));
        if (windows.size() == 1) {
            sb.append(" to the ").append(windows.keySet().iterator().next()).append(" window");
        } else if (!windows.isEmpty()) {
            List<String> to = new ArrayList<>();
            new TreeMap<>(windows).forEach((w, n) -> to.add(w + " " + n));
            sb.append(" to windows ").append(String.join(", ", to));
        }
        if (!outcomes.isEmpty()) {
            List<Map.Entry<String, Long>> by = new ArrayList<>(outcomes.entrySet());
            // The most first, and alike counts in their words' order, so the same pass always reads the same.
            by.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            List<String> said = new ArrayList<>();
            for (Map.Entry<String, Long> e : by) {
                said.add(e.getValue() + " " + e.getKey());
            }
            sb.append(": ").append(String.join(", ", said));
        }
        return sb.toString();
    }

    /** What a change's outcome, as the reconciler names it, did, in words. */
    static String outcome(String named) {
        return switch (named) {
            case "unchanged" -> "did nothing";
            case "layout" -> "relaid it out";
            case "geometry" -> "moved something";
            case "draw" -> "redrew it";
            case "queued" -> "queued a reveal";
            case "missing" -> "found no node";
            default -> named;
        };
    }

    private static Map<String, Long> add(Map<String, Long> to, String key, long n) {
        Map<String, Long> out = new java.util.HashMap<>(to);
        out.merge(key, n, Long::sum);
        return out;
    }

    private static long count(String detail) {
        try {
            return Long.parseLong(detail.strip());
        } catch (NumberFormatException notACount) {
            return -1L;
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }
}
