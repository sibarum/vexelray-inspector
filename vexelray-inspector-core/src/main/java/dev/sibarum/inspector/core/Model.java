package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <h2>The event schema</h2>
 *
 * <p>Every event says what is known of it as fields, named from {@link #FIELDS} and kept in that order, the same names
 * on every lane so that one filter reads them all: {@link #TYPE}, {@link #FROM} (what set it off), {@link #TO} (where
 * it went), {@link #PASS}, {@link #TOOK} and {@link #THREAD}, then those of its type alone. A field the rows cannot
 * fill is absent, never a placeholder: {@link #TO} is absent from every event until the bus records a message's
 * receivers (framework-notes FN-10).
 *
 * <h2>What a pass's rows say of it</h2>
 *
 * <p>Every row carries the pass it was written in, and the loop's own rows around its {@code frame} zone fill a pass's
 * fields: a {@code wake} in pass {@code F} (in its work or its park) is what woke {@code F + 1}, its {@link #FROM};
 * {@code draw skipped} and a draw held come before the zone's row, {@code frame.present} or {@code frame.update},
 * {@code loop.park} and {@code wait for events} after it. Those before wait in {@link #ahead} until their pass is
 * folded; those after are set on its mark. This is the recorder's {@code why} (vexelray-gui's {@code Frames}), read
 * from the same rows.
 *
 * <h2>What set an event off</h2>
 *
 * <p>A row's cause names a source by its key ({@code t03}) and a firing by its number. The run names its sources in
 * {@code cause.source} rows, which the link also hands over when the stream opens, and each automation command in an
 * {@code agent.command} row in that command's own cause: both are kept in {@link #names} for as long as the run is,
 * and {@link #causeOf} turns a cause into words with them.
 *
 * @param newestNanos the newest moment the rows folded so far reach, on the machine's clock
 * @param lanes       every lane, in {@link #LANES}' order, each with its events over the reach before that moment
 * @param lost        rows the link lost, in every batch folded so far
 * @param clockOffset what the run's wall clock said less the machine's monotonic clock, in nanoseconds, from the
 *                    first row folded that said its wall clock; 0 before one has
 * @param names       what the run called its cause sources, by key ({@code t03} to {@code gui.frame}), and its
 *                    automation commands, by cause without the process ({@code c11.2} to {@code key SPACE})
 * @param ahead       the fields rows have filled of passes not folded yet, by pass number: what woke one, a draw held
 * @param probed      the lanes the run's probe records, by key, as its link said; empty when it has not said, and
 *                    then every lane is taken as recorded ({@link Lane#recorded})
 */
public record Model(long newestNanos, List<Lane> lanes, long lost, long clockOffset, Map<String, String> names,
                    Map<Long, List<Field>> ahead, List<String> probed) {

    /** The event schema's fields, in the order every event keeps them; see the class comment. */
    public static final String TYPE = "Type";
    public static final String FROM = "From";
    public static final String TO = "To";
    public static final String PASS_NUMBER = "Pass";
    public static final String TOOK = "Took";
    public static final String THREAD = "Thread";
    /** A pass's: whether it drew, or why not; the most it parked for after; how long it waited. */
    public static final String DREW = "Drew";
    public static final String PARKED = "Parked";
    public static final String WAITED = "Waited";
    /** A message's: its type's package. */
    public static final String PACKAGE = "Package";
    /** Injected input's: the automation verb, and what it sent. */
    public static final String VERB = "Verb";
    public static final String SENT = "Sent";
    /** Input from the devices: how many key edges, presses and releases. */
    public static final String EDGES = "Edges";
    /** A draw held's: what it was held for. */
    public static final String REASON = "Reason";
    public static final List<String> FIELDS = List.of(TYPE, FROM, TO, PASS_NUMBER, TOOK, THREAD, DREW, PARKED, WAITED,
            PACKAGE, VERB, SENT, EDGES, REASON);

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
    static final Model EMPTY = new Model(0L, LANES.stream().map(n -> new Lane(n, List.of())).toList(), 0L, 0L,
            Map.of(), Map.of(), List.of());

    /** The rows that name what a cause is: on the probe's {@code app} lane. */
    static final String APP_LANE = "app";
    static final String SOURCE = "cause.source";
    static final String COMMAND = "agent.command";
    /** The row {@link LinkRows} adds for the lanes the run's probe records: its detail, their keys a space apart. */
    static final String RECORDED = "probe.lanes";

    /** The probe lane each of {@link #LANES} is read from, in that order; see the class comment. */
    private static final List<String> PROBE_LANES = List.of("input", "frame", "bus", "bus", "gpu", "gpu");

    /**
     * Whether a run whose probe records {@code probe} records the rows lane {@code lane} is read from; true when the run
     * has not said what it records, so that no lane is said to be missing from a run that never told.
     */
    static boolean records(String lane, List<String> probe) {
        return probe.isEmpty() || probe.contains(PROBE_LANES.get(LANES.indexOf(lane)));
    }

    /** How many passes back a word about a pass not yet folded is kept for before it is let go. */
    private static final long AHEAD_PASSES = 4;

    public Model {
        lanes = List.copyOf(lanes);
        names = Map.copyOf(names);
        ahead = Map.copyOf(ahead);
        probed = List.copyOf(probed);
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
        Map<String, String> named = names;
        Map<Long, List<Field>> told = ahead;
        List<String> probe = probed;
        List<List<Mark>> next = new ArrayList<>(lanes.size());
        boolean[] changed = new boolean[lanes.size()];
        for (Lane l : lanes) {
            next.add(l.marks());
        }
        int main = LANES.indexOf(MAIN);
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
            if (r.lane().equals(APP_LANE)) {
                if (r.kind().equals(RECORDED)) {
                    probe = r.detail().isBlank() ? List.of() : List.of(r.detail().trim().split(" +"));
                    continue;
                }
                named = learn(named, r);
                continue;
            }
            Field about = r.frame() > 0 ? aboutPass(r, named) : null;
            if (about != null) {
                long pass = r.kind().equals("wake") ? r.frame() + 1 : r.frame();
                // The first wake is what woke it, and a reason a draw was held outranks that it only updated.
                if (!setOn(next, changed, main, pass, about)) {
                    List<Field> before = told.getOrDefault(pass, List.of());
                    if (before.stream().noneMatch(f -> f.name().equals(about.name()))) {
                        List<Field> after = new ArrayList<>(before);
                        after.add(about);
                        told = withPass(told, pass, after);
                    }
                }
            }
            int lane = laneOf(r);
            if (lane < 0) {
                continue;
            }
            Mark m = markOf(r, named);
            if (m == null) {
                continue;
            }
            if (lane == main && m.frame() > 0) {
                // A pass: what was said of it before its own row, and nothing older than a few passes before it.
                for (Field f : told.getOrDefault(m.frame(), List.of())) {
                    m = m.with(f.name(), f.value(), false);
                }
                long pass = m.frame();
                if (told.keySet().stream().anyMatch(k -> k <= pass)) {
                    Map<Long, List<Field>> kept = new HashMap<>(told);
                    kept.keySet().removeIf(k -> k <= pass || k > pass + AHEAD_PASSES);
                    told = kept;
                }
            }
            if (!changed[lane]) {
                next.set(lane, new ArrayList<>(next.get(lane)));
                changed[lane] = true;
            }
            next.get(lane).add(m);
        }
        long from = Bench.since(newest, reachNanos);
        List<Lane> out = new ArrayList<>(lanes.size());
        for (int i = 0; i < lanes.size(); i++) {
            List<Mark> marks = next.get(i);
            if (!marks.isEmpty() && marks.getFirst().toNanos() < from) {
                marks = changed[i] ? marks : new ArrayList<>(marks);
                marks.removeIf(m -> m.toNanos() < from);
                changed[i] = true;
            }
            boolean recorded = records(LANES.get(i), probe);
            out.add(changed[i] || recorded != lanes.get(i).recorded() ? new Lane(LANES.get(i), marks, recorded)
                    : lanes.get(i));
        }
        return new Model(newest, out, lostNow, offset, named, told, probe);
    }

    /** What {@link #DREW} says of a pass that did not draw because it did not need to, or could not. */
    public static final String DREW_YES = "yes";
    public static final String DREW_UPDATED = "no, it only updated";
    public static final String DREW_NOTHING = "no, nothing had changed";
    public static final String DREW_CEILING = "no, held for the frame-rate ceiling";
    public static final String DREW_GPU = "no, held for the GPU";

    /**
     * The field a loop row fills of its pass, or null when it fills none: what woke the next ({@link #FROM}), whether
     * it drew or why not ({@link #DREW}), and how long it parked and waited after.
     */
    private static Field aboutPass(Row r, Map<String, String> names) {
        if (!r.lane().equals("frame")) {
            return null;
        }
        return switch (r.kind()) {
            case "wake" -> {
                String by = causeOf(r.cause(), names);
                yield new Field(FROM, r.detail() + (by.isEmpty() ? "" : ", set off by " + by));
            }
            case "draw skipped" -> new Field(DREW, DREW_NOTHING);
            case HELD_FOR_CEILING -> new Field(DREW, DREW_CEILING);
            case HELD_FOR_GPU -> new Field(DREW, DREW_GPU);
            case "frame.present" -> new Field(DREW, DREW_YES);
            case "frame.update" -> new Field(DREW, DREW_UPDATED);
            case "loop.park" -> new Field(PARKED, r.detail());
            case "wait for events" -> new Field(WAITED, r.detail());
            default -> null;
        };
    }

    /**
     * Set {@code about} on lane {@code lane}'s mark of pass {@code pass}, if it is folded, keeping what is already
     * there (a reason it did not draw over that it only updated); whether it was folded.
     */
    private static boolean setOn(List<List<Mark>> next, boolean[] changed, int lane, long pass, Field about) {
        List<Mark> marks = next.get(lane);
        for (int i = marks.size() - 1; i >= 0; i--) {
            Mark m = marks.get(i);
            if (m.frame() < pass) {
                return false;
            }
            if (m.frame() == pass) {
                if (!changed[lane]) {
                    marks = new ArrayList<>(marks);
                    next.set(lane, marks);
                    changed[lane] = true;
                }
                marks.set(i, m.with(about.name(), about.value(), true));
                return true;
            }
        }
        return false;
    }

    /** {@code names} with what an {@code app} row names, if it names a source or an automation command. */
    private static Map<String, String> learn(Map<String, String> names, Row r) {
        if (r.kind().equals(SOURCE)) {
            int space = r.detail().indexOf(' ');
            if (space > 0) {
                return with(names, r.detail().substring(0, space), r.detail().substring(space + 1));
            }
        } else if (r.kind().equals(COMMAND) && !r.cause().isEmpty()) {
            // "#1 key SPACE": the connection's number, then the command.
            String said = r.detail();
            int space = said.indexOf(' ');
            return with(names, firing(r.cause()), said.startsWith("#") && space > 0 ? said.substring(space + 1) : said);
        }
        return names;
    }

    private static Map<Long, List<Field>> withPass(Map<Long, List<Field>> map, long pass, List<Field> fields) {
        Map<Long, List<Field>> out = new HashMap<>(map);
        out.put(pass, List.copyOf(fields));
        return out;
    }

    private static <K> Map<K, String> with(Map<K, String> map, K key, String value) {
        if (value.equals(map.get(key))) {
            return map;
        }
        Map<K, String> out = new HashMap<>(map);
        out.put(key, value);
        return out;
    }

    /** A cause without its process ({@code c11.2}), which names one firing of one source. */
    private static String firing(String cause) {
        int dot = cause.indexOf('.');
        return dot < 0 ? cause : cause.substring(dot + 1);
    }

    /** What set off work in {@code cause}, in words; empty for none. See {@link #causeOf(String, Map)}. */
    public String causeOf(String cause) {
        return causeOf(cause, names);
    }

    /**
     * What set off work in {@code cause}, in words, with what the run called its sources and commands: an automation
     * command by what it said ({@code automation command "key SPACE"}), a source by its kind and name and which of its
     * firings it was ({@code timer gui.frame, firing 64}), and the cause itself after, as the probe's files have it.
     * Empty for no cause; a cause that is not one is said as it is.
     */
    static String causeOf(String cause, Map<String, String> names) {
        if (cause == null || cause.isEmpty()) {
            return "";
        }
        String[] parts = cause.split("\\.");
        if (parts.length != 3 || parts[1].length() < 2) {
            return cause;
        }
        String key = parts[1];
        long firing;
        try {
            firing = Long.parseLong(parts[2], 16);
        } catch (NumberFormatException notHex) {
            return cause;
        }
        String command = names.get(firing(cause));
        if (key.charAt(0) == 'c' && command != null) {
            return "automation command \"" + command + "\"   (" + cause + ")";
        }
        String kind = switch (key.charAt(0)) {
            case 'i' -> "input";
            case 't' -> "timer";
            case 'c' -> "automation command";
            case 'o' -> "I/O";
            case 'm' -> "mailbox";
            case 'b' -> "start-up";
            default -> "source";
        };
        String name = names.getOrDefault(key, "not named by the run");
        if (name.startsWith("source ")) {
            name = name.substring("source ".length());
        }
        return kind + " " + name + (firing == 0 ? ", on its own" : ", firing " + firing) + "   (" + cause + ")";
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
    private static Mark markOf(Row r, Map<String, String> names) {
        long at = r.atNanos();
        String kind = r.kind();
        if (kind.equals("edges")) {
            long edges = count(r.detail());
            return edges > 0 ? moment(r, at, INPUT_TAKEN, "the devices").with(EDGES, Long.toString(edges), false)
                    : null;
        }
        String from = causeOf(r.cause(), names);
        if (VERBS.contains(kind)) {
            return moment(r, at, INJECTED, from).with(VERB, kind, false).with(SENT, r.detail(), false);
        }
        if (kind.equals(HELD_FOR_CEILING) || kind.equals(HELD_FOR_GPU)) {
            return moment(r, at, kind, from).with(REASON,
                    kind.equals(HELD_FOR_CEILING) ? "the frame-rate ceiling's next slot had not come"
                            : "the GPU had not finished the last frame", false);
        }
        if (r.lane().equals("bus") && isMessageType(kind)) {
            return moment(r, at, MESSAGE, from).with(TYPE, typeName(kind), false)
                    .with(PACKAGE, typePackage(kind), false);
        }
        long took = Durations.nanos(r.detail());
        if (took < 0) {
            return null;
        }
        String what = kind.equals("frame") ? PASS : kind.equals("tick") ? TICK : kind.equals("present frame") ? DRAW
                : kind;
        return fields(new Mark(at - took, at, what, r.thread(), r.frame(), r.cause(), List.of()), from)
                .with(TOOK, String.format(Locale.ROOT, "%.2f ms", took / 1e6), false);
    }

    /** A moment {@code r} said, with its pass, cause and the fields every event has. */
    private static Mark moment(Row r, long at, String kind, String from) {
        return fields(new Mark(at, at, kind, r.thread(), r.frame(), r.cause(), List.of()), from);
    }

    /** {@code m} with the fields every event has: its type, what set it off, its pass and its thread. */
    private static Mark fields(Mark m, String from) {
        return m.with(TYPE, m.kind(), false).with(FROM, from, false)
                .with(PASS_NUMBER, m.frame() > 0 ? "#" + m.frame() : "", false).with(THREAD, m.thread(), false);
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
