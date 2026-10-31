package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the panels show at one moment of a run (vexelray-inspector's {@code docs/design.md} section 8): the newest
 * moment its rows reach, its lanes' events over the {@link Bench#REACH} before it, and the rows its link lost.
 * Replaced, never changed.
 *
 * <p>{@link #fold} is the one way a model moves on, and it is pure: the bench folds each batch on live with it, and
 * {@link Tape#asOf} folds a second's rows onto that second's checkpoint with it. Because it is the same function both
 * ways, the model as of a moment in the past is exactly the model the bench held live at that moment. A new lane or
 * readout is a field here and a few lines in {@link #fold}, and rewinds with no more work.
 *
 * <h2>Which row is which event</h2>
 *
 * <p>Read from what 0.2 records today (design.md section 7), checked against a traced run's rows. A zone writes one
 * row at its end whose detail is how long it took, so its event runs back from the row by that long; a count or a
 * mark is a moment.
 *
 * <ul>
 *   <li><b>Input</b>: tactroller's {@code edges} count on the {@code input} lane, when it counted any; and what an
 *       automation verb injected ({@code key}, {@code type}, ...), told apart as {@link #INJECTED}.</li>
 *   <li><b>Main thread</b>: the {@code frame} zone, one a pass of the loop.</li>
 *   <li><b>Logic</b>: the {@code tick} zone on the {@code bus} lane, on any thread but {@code main}, whose own tick
 *       is the widgets'. Under {@code --logic=main} the logic's ticks are on {@code main} too and cannot be told from
 *       the widgets' (framework-notes FN-8), so this lane is empty there.</li>
 *   <li><b>Messages</b>: a row on the {@code bus} lane whose kind is a message's type, a class name
 *       ({@code dev.sibarum.inspector.core.Messages.Rows}), as a moment on the thread that published it; not the
 *       bus's own bookkeeping ({@code tick}, {@code pump drain}, {@code vexelray.gui.main.mutations depth},
 *       {@code uncaused main}), whose kinds are no class name.</li>
 *   <li><b>Drawing</b>: the presenter's {@code present frame} zone, one a draw, from recording to present; and the
 *       loop's {@code draw held for the ceiling} and {@code draw held for the GPU} counts, as moments. A draw is not
 *       numbered yet (design.md 7½), so it is placed by its time alone.</li>
 *   <li><b>GPU</b>: what the presenter waited on: {@code wait fence}, {@code dxgi wait}, {@code acquire image}.</li>
 * </ul>
 *
 * @param newestNanos the newest moment the rows folded so far reach, on the machine's clock
 * @param lanes       every lane, in {@link #LANES}' order, each with its events over the reach before that moment
 * @param lost        rows the link lost, in every batch folded so far
 * @param clockOffset what the run's wall clock said less the machine's monotonic clock, in nanoseconds, from the
 *                    first row folded that said its wall clock; 0 before one has
 */
public record Model(long newestNanos, List<Lane> lanes, long lost, long clockOffset) {

    /** The lanes' names, top to bottom, which is also the order of {@link #lanes}. */
    public static final String INPUT = "Input";
    public static final String MAIN = "Main thread";
    public static final String LOGIC = "Logic";
    public static final String MESSAGES = "Messages";
    public static final String DRAWING = "Drawing";
    public static final String GPU = "GPU";
    public static final List<String> LANES = List.of(INPUT, MAIN, LOGIC, MESSAGES, DRAWING, GPU);

    /** The events' kinds, in words the status line puts first. */
    public static final String INPUT_TAKEN = "input";
    public static final String INJECTED = "injected input";
    public static final String PASS = "pass";
    public static final String TICK = "tick";
    public static final String DRAW = "draw";
    public static final String HELD_FOR_CEILING = "draw held for the ceiling";
    public static final String HELD_FOR_GPU = "draw held for the GPU";
    public static final String MESSAGE = "message";

    /** The row {@link LinkRows} adds to a batch for the rows its link lost: its detail is how many. */
    static final String LOST_LANE = "link";
    static final String LOST = "lost";

    /** How far back the readouts look from the model's moment. */
    static final long READOUT_NANOS = 1_000_000_000L;

    /** The automation verbs that write an input row: what was injected rather than taken from a device. */
    private static final List<String> VERBS = List.of("key", "keydown", "keyup", "type");

    /** What the presenter waits on. */
    private static final List<String> WAITS = List.of("wait fence", "dxgi wait", "acquire image");

    /** Before any row. */
    static final Model EMPTY = new Model(0L, LANES.stream().map(n -> new Lane(n, List.of())).toList(), 0L, 0L);

    public Model {
        lanes = List.copyOf(lanes);
    }

    /** The lane named {@code name}, which is one of {@link #LANES}. */
    public Lane lane(String name) {
        return lanes.get(LANES.indexOf(name));
    }

    /** What the run's wall clock said at {@code atNanos}, in nanoseconds since the epoch; -1 when no row has said. */
    public long clockAt(long atNanos) {
        return clockOffset == 0L ? -1L : atNanos + clockOffset;
    }

    /**
     * This model with {@code rows} folded on, in their order, leaving out any stamped after {@code upToNanos}; events
     * that ended before the reach are let go.
     */
    Model fold(List<Row> rows, long upToNanos) {
        return fold(rows, upToNanos, Bench.REACH);
    }

    /** {@link #fold(List, long)} keeping the events over {@code reachNanos} instead of the panels' reach. */
    Model fold(List<Row> rows, long upToNanos, long reachNanos) {
        long newest = newestNanos;
        long lostNow = lost;
        long offset = clockOffset;
        List<List<Mark>> next = new ArrayList<>(lanes.size());
        boolean[] changed = new boolean[lanes.size()];
        for (Lane l : lanes) {
            next.add(l.marks());
        }
        for (Row r : rows) {
            if (r.atNanos() > upToNanos) {
                continue;
            }
            newest = Math.max(newest, r.atNanos());
            if (offset == 0L && r.wallNanos() != 0L) {
                offset = r.wallNanos() - r.atNanos();
            }
            if (r.lane().equals(LOST_LANE) && r.kind().equals(LOST)) {
                lostNow += Long.parseLong(r.detail());
                continue;
            }
            int lane = laneOf(r);
            if (lane < 0) {
                continue;
            }
            Mark m = markOf(r);
            if (m == null) {
                continue;
            }
            if (!changed[lane]) {
                next.set(lane, new ArrayList<>(next.get(lane)));
                changed[lane] = true;
            }
            next.get(lane).add(m);
        }
        long from = newest - reachNanos;
        List<Lane> out = new ArrayList<>(lanes.size());
        for (int i = 0; i < lanes.size(); i++) {
            List<Mark> marks = next.get(i);
            if (!marks.isEmpty() && marks.getFirst().toNanos() < from) {
                marks = changed[i] ? marks : new ArrayList<>(marks);
                marks.removeIf(m -> m.toNanos() < from);
                changed[i] = true;
            }
            out.add(changed[i] ? new Lane(LANES.get(i), marks) : lanes.get(i));
        }
        return new Model(newest, out, lostNow, offset);
    }

    /** Which of {@link #LANES} a row belongs on, by where it came from; -1 for none. */
    private static int laneOf(Row r) {
        String lane = r.lane();
        String kind = r.kind();
        if (lane.equals("input") && (kind.equals("edges") || VERBS.contains(kind))) {
            return LANES.indexOf(INPUT);
        }
        if (lane.equals("frame") && kind.equals("frame")) {
            return LANES.indexOf(MAIN);
        }
        if (lane.equals("bus") && kind.equals("tick") && !r.thread().equals("main")) {
            return LANES.indexOf(LOGIC);
        }
        if (lane.equals("bus") && isMessageType(kind)) {
            return LANES.indexOf(MESSAGES);
        }
        if ((lane.equals("gpu") && kind.equals("present frame"))
                || (lane.equals("frame") && (kind.equals(HELD_FOR_CEILING) || kind.equals(HELD_FOR_GPU)))) {
            return LANES.indexOf(DRAWING);
        }
        if (lane.equals("gpu") && WAITS.contains(kind)) {
            return LANES.indexOf(GPU);
        }
        return -1;
    }

    /**
     * Whether a bus row's kind is a message's type: a class name, dotted, with no space, its last part capitalised
     * ({@code dev.vexelray.framework.core.WindowOpened}), unlike the bus's own rows ({@code vexelray.gui.main.node.resize},
     * {@code pump drain}).
     */
    static boolean isMessageType(String kind) {
        int dot = kind.lastIndexOf('.');
        return dot > 0 && dot < kind.length() - 1 && kind.indexOf(' ') < 0
                && Character.isUpperCase(kind.charAt(dot + 1));
    }

    /** The event a row on a lane says, or null when it says none (an {@code edges} count of nothing). */
    private static Mark markOf(Row r) {
        long at = r.atNanos();
        String kind = r.kind();
        String thread = r.thread();
        if (kind.equals("edges")) {
            return count(r.detail()) > 0 ? new Mark(at, at, INPUT_TAKEN, thread, "") : null;
        }
        if (VERBS.contains(kind)) {
            return new Mark(at, at, INJECTED, thread, "");
        }
        if (kind.equals(HELD_FOR_CEILING) || kind.equals(HELD_FOR_GPU)) {
            return new Mark(at, at, kind, thread, "");
        }
        if (r.lane().equals("bus") && isMessageType(kind)) {
            return new Mark(at, at, MESSAGE, thread, kind);
        }
        long took = Durations.nanos(r.detail());
        if (took < 0) {
            return null;
        }
        String what = kind.equals("frame") ? PASS : kind.equals("tick") ? TICK : kind.equals("present frame") ? DRAW
                : kind;
        return new Mark(at - took, at, what, thread, "");
    }

    /**
     * A message type's name without its package: from its first capitalised part, so a nested type keeps its outer
     * one ({@code Messages.Rows}).
     */
    public static String typeName(String type) {
        int from = 0;
        for (int dot = type.indexOf('.'); dot >= 0; dot = type.indexOf('.', dot + 1)) {
            if (Character.isUpperCase(type.charAt(from))) {
                break;
            }
            from = dot + 1;
        }
        return type.substring(from);
    }

    /** A message type's package: all of it before {@link #typeName}; empty when it has none. */
    public static String typePackage(String type) {
        int at = type.length() - typeName(type).length();
        return at == 0 ? "" : type.substring(0, at - 1);
    }

    private static long count(String detail) {
        try {
            return Long.parseLong(detail.strip());
        } catch (NumberFormatException notACount) {
            return 0L;
        }
    }

    /** The readouts over the second before this model's moment. */
    Readouts readouts(String run) {
        long from = newestNanos - READOUT_NANOS;
        int draws = 0;
        int ceiling = 0;
        int gpu = 0;
        for (Mark m : lane(DRAWING).marks()) {
            if (m.toNanos() <= from) {
                continue;
            }
            draws += m.kind().equals(DRAW) ? 1 : 0;
            ceiling += m.kind().equals(HELD_FOR_CEILING) ? 1 : 0;
            gpu += m.kind().equals(HELD_FOR_GPU) ? 1 : 0;
        }
        long[] ticks = lane(LOGIC).marks().stream().filter(m -> m.toNanos() > from)
                .mapToLong(m -> m.toNanos() - m.fromNanos()).toArray();
        Arrays.sort(ticks);
        return new Readouts(run, draws, ceiling, gpu, ticks.length, percentile(ticks, 0.50), percentile(ticks, 0.95),
                lost);
    }

    /** The {@code p} percentile of {@code sorted}, nearest rank; 0 when empty. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)];
    }
}
