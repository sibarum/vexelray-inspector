package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Messages.Range;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Traced;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 *       automation verb injected ({@code key}, {@code type}, a click's {@code pointer.press}, ...), told apart as
 *       {@link #INJECTED}.</li>
 *   <li><b>Main thread</b>: the {@code frame} zone, one a pass of the loop.</li>
 *   <li><b>Logic</b>: the {@code tick} zone on the {@code bus} lane, on any thread but {@code main}, whose own tick
 *       is the widgets'; and on {@code main}, under {@code --logic=main}, the one whose {@code tick.ran} row names it
 *       the logic's ({@code #12 logic ...}), held until that row says so (FN-16). The {@code tick.ran} row after the
 *       zone numbers the tick and says its rounds and deliveries.</li>
 *   <li><b>Messages</b>: a row on the {@code bus} lane whose kind is a message's type, a class name
 *       ({@code dev.sibarum.inspector.core.Messages.Rows}), as a moment on the thread that published it; not the
 *       bus's own bookkeeping ({@code tick}, {@code pump drain}, {@code vexelray.gui.main.mutations depth},
 *       {@code uncaused main}), whose kinds are no class name.</li>
 *   <li><b>Drawing</b>: the presenter's {@code present frame} zone, one a draw, from recording to present; and the
 *       loop's {@code draw held for the ceiling} and {@code draw held for the GPU} counts, as moments. A draw's own
 *       {@code draw.ran} row, written inside the presenter's zone, numbers it and its pass, and names the causes of the
 *       changes it showed, which is how a path reaches the draw.</li>
 *   <li><b>GPU</b>: what the presenter waited on: {@code wait fence}, {@code dxgi wait}, {@code acquire image}.</li>
 *   <li><b>Jobs</b>: each job's run on the job lane, its {@code job <name>} zone on the {@code bus} lane, from the
 *       request reaching it to its reply sent. A timer's {@code timer} row, naming the topic it sent, is a moment on
 *       the <b>Input</b> lane: like input, it comes from outside the logic.</li>
 * </ul>
 *
 * <h2>The event schema</h2>
 *
 * <p>Every event says what is known of it as fields, named from {@link #FIELDS} and kept in that order, the same names
 * on every lane so that one filter reads them all: {@link #TYPE}, {@link #PATH} (every hop its cause took), {@link
 * #PASS}, {@link #TOOK} and {@link #THREAD}, then those of its type alone. A field the rows cannot fill is absent,
 * never a placeholder.
 *
 * <h2>An event's path</h2>
 *
 * <p>A cause travels: a source sets it off, and every publish, send, delivery, ledger step and change to the GUI made
 * in it writes rows under it, on whichever thread, so the rows sharing a cause are the hops it took, in order.
 * {@link #pathOf} reads them from the rows kept on the tape when an event is shown, rather than the fold keeping every
 * cause's path on each batch: a run of rows from the same place is one hop ({@link Hop}), and an event's path is its
 * cause's source, then each hop. A pass's cause is what woke it, so its path is
 * the path of what made it run, ending at the pass.
 *
 * <h2>What a pass's rows say of it</h2>
 *
 * <p>Every row carries the pass it was written in, and the loop's own rows around its {@code frame} zone fill a pass's
 * fields: a {@code wake} in pass {@code F} (in its work or its park) is what woke {@code F + 1}, its cause;
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
 * @param warning     what the run's link said that makes what is shown less than whole, in words: an application of
 *                    another link version ({@link LinkRows#versionWarning}); empty when nothing has
 * @param declared    the run's bus graph, as its link said it ({@link Declared}); {@link Declared#NONE} before it has
 * @param counts      what crossed each topic, by its name, from the first row folded: see {@link Counted}
 * @param mainTick    the main thread's last {@code tick} zone not yet claimed: under {@code --logic=main} the logic's
 *                    and the widgets' ticks both run there, and only the {@code tick.ran} row after one says whose it
 *                    is (FN-16); {@link #NO_TICK} when there is none
 */
public record Model(long newestNanos, List<Lane> lanes, long lost, long clockOffset, Map<String, String> names,
                    Map<Long, List<Field>> ahead, List<String> probed, String warning, Declared declared,
                    Map<String, Counted> counts, Row mainTick) {

    /**
     * One hop of a cause's path: what happened there, in words, on which thread, when it began, and how many rows in a
     * row said it; for the GUI's changes, how many of them laid it out again and how many drew it again; the first row
     * that said it; and the tick it happened in, {@code #12}, when it was in one that said its number.
     */
    public record Hop(String says, String thread, long atNanos, int count, int relaid, int redrawn, Row row,
                      String tick) {

        /** This hop, said once more, by a row that relaid or redrew the GUI if those say so. */
        Hop again(boolean relays, boolean redraws) {
            return new Hop(says, thread, atNanos, count + 1, relaid + (relays ? 1 : 0), redrawn + (redraws ? 1 : 0),
                    row, tick);
        }

        /** This hop, placed in tick {@code tick}. */
        Hop in(String tick) {
            return new Hop(says, thread, atNanos, count, relaid, redrawn, row, tick);
        }

        /** This hop in words, as a path says it. */
        public String said() {
            String in = tick.isEmpty() ? "" : " in tick " + tick;
            if (says.equals(GUI_CHANGED)) {
                return count + (count == 1 ? " change" : " changes") + " to the GUI on " + thread + in
                        + (relaid + redrawn == 0 ? "" : " (" + relaid + " relaid out, " + redrawn + " redrawn)");
            }
            return says + in + (count > 1 ? " ×" + count : "");
        }
    }

    /**
     * What crossed one topic: messages written on it and deliveries of it, in all and by whom. A component's write is a
     * {@code send} row, naming it; a write from outside a tick (a source's, a timer's, a widget's) is a row whose kind is
     * the topic, naming nobody, and is counted by the thread that wrote it, after {@link #OUTSIDE}; a delivery is a
     * {@code receive} row, naming the mailbox ({@code class#method}) or else the inbox it reached.
     *
     * @param by what each sender wrote, by its name, or by {@link #OUTSIDE} and its thread's
     * @param to   what was delivered to each receiver, by its name
     * @param last the moment of each sender's newest write, by the same names as {@code by}
     */
    public record Counted(long written, long delivered, Map<String, Long> by, Map<String, Long> to,
                          Map<String, Long> last) {

        /** What a write from outside a tick is counted by, before its thread's name: the row names no sender. */
        public static final String OUTSIDE = "@";

        static final Counted NONE = new Counted(0L, 0L, Map.of(), Map.of(), Map.of());

        public Counted {
            by = Map.copyOf(by);
            to = Map.copyOf(to);
            last = Map.copyOf(last);
        }

        /**
         * What was counted after {@code before}, the same topic's counts as of an earlier moment; this, with none. A
         * sender's newest write is kept only where it wrote after {@code before}: else it wrote none within.
         */
        Counted less(Counted before) {
            if (before == null) {
                return this;
            }
            Map<String, Long> within = new HashMap<>(last.size());
            last.forEach((who, at) -> {
                if (by.getOrDefault(who, 0L) > before.by.getOrDefault(who, 0L)) {
                    within.put(who, at);
                }
            });
            return new Counted(written - before.written, delivered - before.delivered, less(by, before.by),
                    less(to, before.to), within);
        }

        private static Map<String, Long> less(Map<String, Long> now, Map<String, Long> then) {
            Map<String, Long> out = new HashMap<>(now.size());
            now.forEach((who, n) -> out.put(who, n - then.getOrDefault(who, 0L)));
            return out;
        }
    }

    /** The event schema's fields, in the order every event keeps them; see the class comment. */
    public static final String TYPE = "Type";
    public static final String PATH = "Path";
    public static final String PASS_NUMBER = "Pass";
    public static final String TOOK = "Took";
    public static final String THREAD = "Thread";
    /** A pass's: whether it drew, or why not; the most it parked for after; how long it waited. */
    public static final String DREW = "Drew";
    public static final String PARKED = "Parked";
    public static final String WAITED = "Waited";
    /** A message's: its type's package. */
    public static final String PACKAGE = "Package";
    /**
     * A message's: who wrote it, as the graph and flows panels name its sender ({@link #senderOf}): a part's class, the
     * framework, or {@link #OUTSIDE_A_TICK}.
     */
    public static final String FROM = "From";
    /** Injected input's: the automation verb, and what it sent. */
    public static final String VERB = "Verb";
    public static final String SENT = "Sent";
    /** Input from the devices: how many key edges, presses and releases. */
    public static final String EDGES = "Edges";
    /** A draw held's: what it was held for. */
    public static final String REASON = "Reason";
    /** A tick's, from its {@code tick.ran} row: its number, its rounds, what it delivered, whether it was cut short. */
    public static final String TICK_NUMBER = "Tick";
    public static final String ROUNDS = "Rounds";
    public static final String DELIVERED = "Delivered";
    public static final String CARRIED = "Carried over";
    /** A draw's, from its {@code draw.ran} row, and its pass's: its number, and what set off the changes it showed. */
    public static final String DRAW_NUMBER = "Draw";
    public static final String SHOWED = "Showed";
    /** A draw's, from the {@code emit canvas} zone in its pass: how long recording its commands took on the CPU. */
    public static final String RECORDING = "Recording";

    /** Where {@link #ahead} keeps what a row with no pass said for the next draw: a recording's rows have none. */
    private static final long UNNUMBERED = 0L;
    public static final List<String> FIELDS = List.of(TYPE, PATH, PASS_NUMBER, TICK_NUMBER, DRAW_NUMBER, TOOK, THREAD,
            DREW, PARKED, WAITED, ROUNDS, DELIVERED, CARRIED, SHOWED, RECORDING, FROM, PACKAGE, VERB, SENT, EDGES,
            REASON);

    /** The row a tick writes after its zone, and a draw after its own: atchung's and vexelray-gui's. */
    static final String TICK_RAN = "tick.ran";
    static final String DRAW_RAN = "draw.ran";

    /** Whether {@code r} is a draw's own row: {@code draw.ran #<n> [<cause> ...] [+<more>]}. */
    static boolean isDraw(Row r) {
        return r.kind().equals(DRAW_RAN) && r.lane().equals("draw");
    }

    /** The causes a draw's row says it showed, as the probe writes them; none for any other row. */
    static List<String> shownBy(Row r) {
        if (!isDraw(r)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String[] words = r.detail().split(" ");
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isEmpty() && !words[i].startsWith("+")) {
                out.add(words[i]);
            }
        }
        return out;
    }

    /** The word a {@code tick.ran} row names its tick by, after the number; empty for an unnamed tick. */
    static String tickNameOf(Row r) {
        String[] w = r.detail().split(" ", 3);
        return w.length > 1 && !w[1].isEmpty() && !Character.isDigit(w[1].charAt(0)) ? w[1] : "";
    }

    /**
     * Whether {@code r}, a {@code tick.ran} row, is the logic lane's: every tick off the main thread is, since the
     * logic's coordinator is the only tick on its thread; on the main thread only the one the framework names so,
     * which under {@code --logic=main} tells it from the widgets' (FN-16).
     */
    static boolean isLogicTick(Row r) {
        return !r.thread().equals("main") || tickNameOf(r).equals(LOGIC_TICK);
    }

    /** The number a {@code tick.ran} or {@code draw.ran} row begins with, {@code #12}; empty when it does not. */
    static String numberOf(Row r) {
        String d = r.detail();
        int space = d.indexOf(' ');
        String first = space < 0 ? d : d.substring(0, space);
        return first.startsWith("#") && first.length() > 1 ? first : "";
    }

    /** The lanes' names, top to bottom, which is also the order of {@link #lanes}. */
    public static final String INPUT = "Input";
    public static final String MAIN = "Main thread";
    public static final String LOGIC = "Logic";
    public static final String MESSAGES = "Messages";
    public static final String DRAWING = "Drawing";
    public static final String GPU = "GPU";
    public static final String JOBS = "Jobs";
    public static final List<String> LANES = List.of(INPUT, MAIN, LOGIC, MESSAGES, DRAWING, GPU, JOBS);

    /** The events' kinds, in words the status line puts first. */
    public static final String INPUT_TAKEN = "input";
    public static final String INJECTED = "injected input";
    public static final String PASS = "pass";
    public static final String TICK = "tick";
    public static final String DRAW = "draw";
    public static final String HELD_FOR_CEILING = "draw held for the ceiling";
    public static final String HELD_FOR_GPU = "draw held for the GPU";
    public static final String MESSAGE = "message";
    public static final String JOB = "job";
    public static final String TIMER = "timer";

    /** A job's zone on the bus lane, {@code job <its class's simple name>} (vexelray-framework's {@code Jobs}). */
    static final String JOB_ZONE = "job ";

    /** The row {@link LinkRows} adds to a batch for the rows its link lost: its detail is how many. */
    static final String LOST_LANE = "link";
    static final String LOST = "lost";

    /** How far back the readouts look from the model's moment. */
    static final long READOUT_NANOS = 1_000_000_000L;

    /**
     * The automation verbs that write an input row: what was injected rather than taken from a device. The pointer's are
     * vexelray-gui-automation's {@code Cursor}: a {@code click} is a press and a release, and getting there moves.
     */
    private static final List<String> VERBS = List.of("key", "keydown", "keyup", "type", "pointer.press",
            "pointer.release", "pointer.scroll", "pointer.move");

    /** What the presenter waits on. */
    private static final List<String> WAITS = List.of("wait fence", "dxgi wait", "acquire image");

    /** No tick zone waiting to be claimed ({@link #mainTick}). Before {@link #EMPTY}, which holds it. */
    static final Row NO_TICK = new Row(-1, 0L, "", "", "", "");

    /** What the framework names the logic lane's tick in its {@code tick.ran} rows (vexelray-framework's Logic). */
    static final String LOGIC_TICK = "logic";

    /** Before any row. */
    static final Model EMPTY =new Model(0L, LANES.stream().map(n -> new Lane(n, List.of())).toList(), 0L, 0L,
            Map.of(), Map.of(), List.of(), "", Declared.NONE, Map.of(), NO_TICK);

    /** The rows that name what a cause is: on the probe's {@code app} lane. */
    static final String APP_LANE = "app";
    static final String SOURCE = "cause.source";
    static final String COMMAND = "agent.command";
    /** The row {@link LinkRows} adds for the lanes the run's probe records: its detail, their keys a space apart. */
    static final String RECORDED = "probe.lanes";
    /** The row {@link LinkRows} adds when the run's link is of another version: its detail, the warning in words. */
    static final String WARNING = "link.warning";
    /** The row {@link LinkRows} adds for the run's bus graph: its detail, the graph's text, or why there is none. */
    static final String GRAPH = "bus.graph";

    /** The probe lane each of {@link #LANES} is read from, in that order; see the class comment. */
    private static final List<String> PROBE_LANES = List.of("input", "frame", "bus", "bus", "gpu", "gpu", "bus");

    /**
     * Whether a run whose probe records {@code probe} records the rows lane {@code lane} is read from; true when the
     * run has not said what it records, so that no lane is said to be missing from a run that never told.
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
        counts = Map.copyOf(counts);
    }

    /** This model warning of {@code warning} instead; empty to warn of nothing. */
    Model withWarning(String warning) {
        return new Model(newestNanos, lanes, lost, clockOffset, names, ahead, probed, warning, declared, counts,
                mainTick);
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
     * that ended before the reach are let go, from the oldest folded on, so one still inside it keeps those folded after
     * it until it goes. A batch adds to the lanes without copying them ({@link Appended}).
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
        String warned = warning;
        Declared graph = declared;
        Row onMain = mainTick;
        // What this batch counted, by topic: merged onto the counts once, at the end, rather than copied for each row.
        HashMap<String, Tally> tallies = null;
        // Each lane's events as they were, replaced only where a row changes one, and what this batch adds to them.
        List<Appended<Mark>> held = new ArrayList<>(lanes.size());
        List<List<Mark>> added = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            held.add(Appended.of(l.marks()));
            added.add(null);
        }
        boolean[] changed = new boolean[lanes.size()];
        int main = LANES.indexOf(MAIN);
        int drawing = LANES.indexOf(DRAWING);
        int messages = LANES.indexOf(MESSAGES);
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
            if (r.lane().equals("bus")) {
                tallies = tally(tallies, r);
            }
            if (r.lane().equals(APP_LANE)) {
                if (r.kind().equals(GRAPH)) {
                    graph = r.detail().startsWith("# ") ? Declared.parse(r.detail()) : Declared.none(r.detail());
                    continue;
                }
                if (r.kind().equals(RECORDED)) {
                    probe = r.detail().isBlank() ? List.of() : List.of(r.detail().trim().split(" +"));
                    continue;
                }
                if (r.kind().equals(WARNING)) {
                    warned = r.detail();
                    continue;
                }
                named = learn(named, r);
                continue;
            }
            if (isDraw(r)) {
                // Written inside the presenter's zone, so before the draw's mark: kept for its pass, which the draw's
                // mark and the pass's own both take (a recording has no pass numbers, so its draws stay unnumbered).
                if (r.frame() > 0) {
                    List<Field> after = new ArrayList<>(told.getOrDefault(r.frame(), List.of()));
                    after.add(new Field(DRAW_NUMBER, numberOf(r)));
                    String showed = showed(r, named);
                    if (!showed.isEmpty()) {
                        after.add(new Field(SHOWED, showed));
                    }
                    told = withPass(told, r.frame(), after);
                }
                continue;
            }
            if (r.lane().equals("draw") && r.kind().equals(ClockLedger.RECORDING)) {
                // The draw's commands recorded, inside the presenter's zone and before the draw's mark: kept for its
                // pass, as its number is, or under no pass in a recording, which has none; timed like a mark's took.
                long took = Durations.nanos(r.detail());
                if (took >= 0) {
                    long pass = r.frame() > 0 ? r.frame() : UNNUMBERED;
                    List<Field> after = new ArrayList<>(told.getOrDefault(pass, List.of()));
                    after.add(new Field(RECORDING, String.format(Locale.ROOT, "%.3f ms", took / 1e6)));
                    told = withPass(told, pass, after);
                }
                continue;
            }
            if (r.kind().equals(TICK_RAN) && r.lane().equals("bus")) {
                // After the tick's zone, on its thread: the newest tick there is its own. Under --logic=main the
                // logic's ticks and the widgets' are both on main, and only the name in this row says which this is
                // (FN-16): the logic's zone, held until now, goes on the lane; the widgets' is let go.
                int logic = LANES.indexOf(LOGIC);
                if (r.thread().equals("main")) {
                    Mark zone = onMain == NO_TICK ? null : markOf(onMain);
                    onMain = NO_TICK;
                    if (zone == null || !isLogicTick(r)) {
                        continue;
                    }
                    if (added.get(logic) == null) {
                        added.set(logic, new ArrayList<>());
                    }
                    added.get(logic).add(zone);
                    changed[logic] = true;
                }
                setOnTick(held, added, changed, logic, r);
                continue;
            }
            if (r.lane().equals("bus") && r.kind().equals(TICK) && r.thread().equals("main")) {
                onMain = r;
                continue;
            }
            Field about = r.frame() > 0 ? aboutPass(r) : null;
            if (about != null) {
                long pass = r.kind().equals("wake") ? r.frame() + 1 : r.frame();
                // The first wake is what woke it, and a reason a draw was held outranks that it only updated.
                if (!setOn(held, added, changed, main, pass, about)) {
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
            Mark m = markOf(r);
            if (m == null) {
                continue;
            }
            if (lane == messages) {
                // Who wrote it, as the graph names its senders: what the graph's dots put it on.
                Crossing c = crossing(r);
                if (c != null && c.write()) {
                    m = m.with(FROM, senderOf(graph, c.topic(), c.who()), false);
                }
            }
            if (lane == drawing && m.kind().equals(DRAW) && m.frame() > 0) {
                for (Field f : told.getOrDefault(m.frame(), List.of())) {
                    if (f.name().equals(DRAW_NUMBER) || f.name().equals(SHOWED) || f.name().equals(RECORDING)) {
                        m = m.with(f.name(), f.value(), false);
                    }
                }
            } else if (lane == drawing && m.kind().equals(DRAW) && told.containsKey(UNNUMBERED)) {
                // A recording's draw, with no pass: its recording was kept under no pass, for this draw alone.
                for (Field f : told.get(UNNUMBERED)) {
                    m = m.with(f.name(), f.value(), false);
                }
                Map<Long, List<Field>> kept = new HashMap<>(told);
                kept.remove(UNNUMBERED);
                told = kept;
            }
            if (lane == main && m.frame() > 0) {
                // A pass: what was said of it before its own row, and nothing older than a few passes before it.
                for (Field f : told.getOrDefault(m.frame(), List.of())) {
                    m = f.name().equals(WOKEN_IN) ? m.because(f.value()) : m.with(f.name(), f.value(), false);
                }
                long pass = m.frame();
                if (told.keySet().stream().anyMatch(k -> k <= pass)) {
                    Map<Long, List<Field>> kept = new HashMap<>(told);
                    kept.keySet().removeIf(k -> k <= pass || k > pass + AHEAD_PASSES);
                    told = kept;
                }
            }
            if (added.get(lane) == null) {
                added.set(lane, new ArrayList<>());
            }
            added.get(lane).add(m);
            changed[lane] = true;
        }
        long from = Bench.since(newest, reachNanos);
        List<Lane> out = new ArrayList<>(lanes.size());
        for (int i = 0; i < lanes.size(); i++) {
            Appended<Mark> marks = added.get(i) == null ? held.get(i) : held.get(i).plus(added.get(i));
            // Let go of the events from the oldest on that ended before the reach: those folded in a row's order.
            int gone = 0;
            while (gone < marks.size() && marks.get(gone).toNanos() < from) {
                gone++;
            }
            if (gone > 0) {
                marks = marks.dropFirst(gone);
                changed[i] = true;
            }
            boolean recorded = records(LANES.get(i), probe);
            out.add(changed[i] || recorded != lanes.get(i).recorded() ? new Lane(LANES.get(i), marks, recorded)
                    : lanes.get(i));
        }
        Map<String, Counted> counted = tallies == null ? counts : merged(counts, tallies);
        return new Model(newest, out, lostNow, offset, named, told, probe, warned, graph, counted, onMain);
    }

    /** The sender named for writes from outside a tick that more than one declared part could have made. */
    static final String OUTSIDE_A_TICK = "outside a tick";

    /**
     * The declared graph and what crossed it as of this model, for the graph and flows panels: every topic the graph
     * names, or, for a run that declared none (a recording), every topic the rows counted. A write from outside a tick
     * names no sender, only its thread, so it is put to the declared sender of the topic that writes on that thread
     * ({@link #writesOn}); to the framework when none does, the widgets' writes on the main thread among them; and to
     * {@link #OUTSIDE_A_TICK} when more than one could have.
     *
     * @param since     the model a second or two before, whose counts the rate is taken from
     * @param overNanos how long before; 0 for no rate
     */
    Flows flows(String run, String why, Model since, long overNanos) {
        return flows(run, why, since, overNanos, newestNanos());
    }

    /** As {@link #flows(String, String, Model, long)}, said to be as of {@code atNanos}: the cursor, when paused. */
    Flows flows(String run, String why, Model since, long overNanos, long atNanos) {
        return flows(run, why, counts, since.counts, overNanos, Optional.empty(), atNanos);
    }

    /**
     * As {@link #flows(String, String, Model, long)}, over the stretch {@code selected} only: every count less
     * {@code from}'s, the model as of its earlier end, this model being as of its later; the rate over its length.
     */
    Flows flowsOver(String run, String why, Model from, Range selected) {
        Map<String, Counted> within = new HashMap<>(counts.size());
        counts.forEach((topic, c) -> within.put(topic, c.less(from.counts.get(topic))));
        return flows(run, why, within, Map.of(), selected.nanos(), Optional.of(selected), selected.toNanos());
    }

    /**
     * The flows from {@code counts}, the rate their written less {@code rateFrom}'s over {@code overNanos}, said to
     * be over {@code selected} when it is given, and as of {@code atNanos}. Each sender's rate is taken the same way,
     * from what it wrote, and its newest write is the newest of the writers it is put together from.
     */
    private Flows flows(String run, String why, Map<String, Counted> counts, Map<String, Counted> rateFrom,
                        long overNanos, Optional<Range> selected, long atNanos) {
        boolean jobsWrite = !lane(JOBS).marks().isEmpty();
        java.util.TreeMap<String, String> topics = new java.util.TreeMap<>(declared.kinds());
        if (topics.isEmpty()) {
            counts.keySet().forEach(t -> topics.put(t, ""));
        }
        Map<String, Part> parts = new HashMap<>();
        declared.parts().forEach(p -> parts.put(p.name(), p));
        List<Flow> flows = new ArrayList<>(topics.size());
        Counted none = Counted.NONE;
        for (Map.Entry<String, String> t : topics.entrySet()) {
            Counted c = counts.getOrDefault(t.getKey(), none);
            Counted then = rateFrom.getOrDefault(t.getKey(), none);
            // What sender (by its name in the counts) wrote a second, over the same as the topic's rate.
            java.util.function.ToDoubleFunction<String> rate = who -> overNanos <= 0 ? 0.0
                    : (c.by().getOrDefault(who, 0L) - then.by().getOrDefault(who, 0L)) * 1e9 / overNanos;
            List<End> from = new ArrayList<>();
            List<End> to = new ArrayList<>();
            List<Part> senders = new ArrayList<>();
            java.util.Set<String> named = new java.util.HashSet<>();
            for (Declared.Line l : declared.ends()) {
                if (!l.topic().equals(t.getKey())) {
                    continue;
                }
                named.add(l.part());
                if (l.sends()) {
                    from.add(new End(l.part(), l.how(), c.by().getOrDefault(l.part(), 0L), rate.applyAsDouble(l.part()),
                            c.last().getOrDefault(l.part(), 0L)));
                    Part p = parts.get(l.part());
                    if (p != null) {
                        senders.add(p);
                    }
                } else {
                    // A mailbox and an inbox are delivered to by the tick, which writes a row for it, and a job's request
                    // by the framework, which writes one as well, in a run whose jobs are seen to write rows at all
                    // (an older one's do not); a source and a destination are handed their messages with none (FN-15).
                    boolean counted = l.how().equals("mailbox") || l.how().equals("inbox")
                            || l.how().equals("request") && (c.to().containsKey(l.part()) || jobsWrite);
                    to.add(new End(l.part(), l.how(), counted ? c.to().getOrDefault(l.part(), 0L) : End.UNCOUNTED));
                }
            }
            c.by().forEach((who, n) -> {
                if (who.startsWith(Counted.OUTSIDE)) {
                    String part = outsider(senders, parts.isEmpty(), who.substring(Counted.OUTSIDE.length()));
                    double perSecond = rate.applyAsDouble(who);
                    long last = c.last().getOrDefault(who, 0L);
                    int at = indexOf(from, part);
                    if (at >= 0) {
                        End e = from.get(at);
                        from.set(at, new End(e.part(), e.how(), e.count() + n, e.perSecond() + perSecond,
                                Math.max(e.lastNanos(), last)));
                    } else {
                        from.add(new End(part, Declared.Line.SENDS, n, perSecond, last));
                    }
                } else if (!named.contains(who)) {
                    from.add(new End(who, Declared.Line.SENDS, n, rate.applyAsDouble(who),
                            c.last().getOrDefault(who, 0L)));
                }
            });
            c.to().forEach((who, n) -> {
                if (!named.contains(who)) {
                    to.add(new End(who, "", n));
                }
            });
            double perSecond = overNanos <= 0 ? 0.0 : (c.written() - then.written()) * 1e9 / overNanos;
            flows.add(new Flow(t.getKey(), t.getValue(), from, to, c.written(), c.delivered(), perSecond));
        }
        return new Flows(run, why.isEmpty() ? declared.why() : why, declared.parts(), flows, selected, atNanos);
    }

    /**
     * The sender a write from outside a tick on {@code thread} is put to, among {@code senders}, the parts the graph
     * declares send its topic: the one that writes on that thread ({@link #writesOn}); the framework when none does;
     * {@link #OUTSIDE_A_TICK} when more than one could have; and only the thread, {@code on <thread>}, with no graph.
     */
    static String outsider(List<Part> senders, boolean graphless, String thread) {
        if (graphless) {
            return "on " + thread;
        }
        List<Part> could = senders.stream().filter(p -> writesOn(p, thread)).toList();
        return could.size() == 1 ? could.getFirst().name() : could.isEmpty() ? End.FRAMEWORK : OUTSIDE_A_TICK;
    }

    /**
     * Who wrote on {@code topic}, as the graph and flows panels name its sender: {@code who} as a write's row names it,
     * a part's class, or {@link Counted#OUTSIDE} and the thread, which is put to a part as {@link #outsider} says.
     */
    static String senderOf(Declared graph, String topic, String who) {
        if (!who.startsWith(Counted.OUTSIDE)) {
            return who;
        }
        List<Part> senders = new ArrayList<>();
        for (Declared.Line l : graph.ends()) {
            if (l.sends() && l.topic().equals(topic)) {
                for (Part p : graph.parts()) {
                    if (p.name().equals(l.part())) {
                        senders.add(p);
                    }
                }
            }
        }
        return outsider(senders, graph.parts().isEmpty(), who.substring(Counted.OUTSIDE.length()));
    }

    /**
     * Whether part {@code p} writes from outside a tick on {@code thread}, as the framework names its threads: a source
     * or a destination on its own ({@code vexel-source-link-rows} for {@code LinkRows}), a job on the job lane's, a
     * component that takes the timer on the timer's, a view or a sender on the main thread.
     */
    static boolean writesOn(Part p, String thread) {
        return switch (p.kind()) {
            case "source" -> thread.startsWith("vexel-source-" + kebab(typeName(p.name())));
            case "destination" -> thread.startsWith("vexel-destination-" + kebab(typeName(p.name())))
                    || thread.startsWith("vexel-source-" + kebab(typeName(p.name())));
            case "job" -> thread.startsWith("vexel-job");
            case "component" -> p.timer() && thread.equals("vexel-timer");
            case "view", "sender" -> thread.equals("main");
            default -> false;
        };
    }

    /** {@code LinkRows} as a thread names it: {@code link-rows}. */
    private static String kebab(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                out.append('-');
            }
            out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }

    private static int indexOf(List<End> ends, String part) {
        for (int i = 0; i < ends.size(); i++) {
            if (ends.get(i).part().equals(part)) {
                return i;
            }
        }
        return -1;
    }

    /** What a batch counts of one topic, before it is merged onto {@link #counts}. */
    private static final class Tally {
        long written;
        long delivered;
        final HashMap<String, Long> by = new HashMap<>();
        final HashMap<String, Long> to = new HashMap<>();
        final HashMap<String, Long> last = new HashMap<>();
    }

    /**
     * {@code tallies} with what bus row {@code r} says crossed a topic, if it says: a write ({@code send}, or a
     * publish from outside a tick, whose kind is the topic) or a delivery ({@code receive}).
     */
    private static HashMap<String, Tally> tally(HashMap<String, Tally> tallies, Row r) {
        Crossing c = crossing(r);
        if (c == null) {
            return tallies;
        }
        HashMap<String, Tally> out = tallies == null ? new HashMap<>() : tallies;
        Tally t = out.computeIfAbsent(c.topic(), k -> new Tally());
        if (c.write()) {
            t.written++;
            t.by.merge(c.who(), 1L, Long::sum);
            t.last.merge(c.who(), r.atNanos(), Math::max);
        } else {
            t.delivered++;
            t.to.merge(c.who(), 1L, Long::sum);
        }
        return out;
    }

    /**
     * A message crossing a topic, as a row says it: a write, by a component ({@code send}, naming it) or from outside a
     * tick ({@link Counted#OUTSIDE} and the thread), or a delivery, to a mailbox ({@code class#method}) or else an inbox.
     */
    record Crossing(String topic, String who, boolean write) {
    }

    /** The crossing {@code r} says, or null when it says none. */
    static Crossing crossing(Row r) {
        String kind = r.kind();
        if (!r.lane().equals("bus")) {
            return null;
        }
        if (kind.equals("send") || kind.equals("receive")) {
            // "<topic> from <sender> <hops>", "<topic> to <inbox> [<mailbox>] <hops>"
            String detail = r.detail();
            boolean write = kind.equals("send");
            int split = detail.indexOf(write ? " from " : " to ");
            int end = detail.lastIndexOf(' ');
            if (split < 0 || end <= split) {
                return null;
            }
            String who = detail.substring(split + (write ? 6 : 4), end);
            int mailbox = who.indexOf(' ');
            if (mailbox > 0) {
                who = who.substring(mailbox + 1);
            }
            return new Crossing(detail.substring(0, split), who, write);
        }
        if (isMessageType(kind)) {
            // The job lane's threads are numbered and taken in turn: one lane, so one sender.
            return new Crossing(kind, Counted.OUTSIDE + r.thread().replaceFirst("^(vexel-job)-\\d+$", "$1"), true);
        }
        return null;
    }

    /** {@code counts} with {@code tallies} added. */
    private static Map<String, Counted> merged(Map<String, Counted> counts, HashMap<String, Tally> tallies) {
        HashMap<String, Counted> out = new HashMap<>(counts);
        for (Map.Entry<String, Tally> e : tallies.entrySet()) {
            Counted before = out.getOrDefault(e.getKey(), Counted.NONE);
            Tally t = e.getValue();
            HashMap<String, Long> by = new HashMap<>(before.by());
            t.by.forEach((k, n) -> by.merge(k, n, Long::sum));
            HashMap<String, Long> to = new HashMap<>(before.to());
            t.to.forEach((k, n) -> to.merge(k, n, Long::sum));
            HashMap<String, Long> last = new HashMap<>(before.last());
            t.last.forEach((k, at) -> last.merge(k, at, Math::max));
            out.put(e.getKey(), new Counted(before.written() + t.written, before.delivered() + t.delivered, by, to,
                    last));
        }
        return out;
    }

    /** What the GUI's changes are said as, a hop of their own that counts them; see {@link Hop#said}. */
    static final String GUI_CHANGED = "changed the GUI";

    /**
     * The hop a row is of its cause's path, in words, or null when it is not one: a publish, a component's send, a
     * delivery to an inbox, a ledger's step, a change to the GUI, the wake of the next pass, or what automation
     * injected. Rows that only count or time the bus's own work are no hop.
     */
    private static String hopOf(Row r) {
        String kind = r.kind();
        return switch (r.lane()) {
            case "bus" -> isMessageType(kind) ? "published " + typeName(kind) + " on " + r.thread()
                    : kind.equals("send") ? said(r.detail(), " from ", "sent ", " from ")
                    : kind.equals("receive") ? said(r.detail(), " to ", "reached ", " at ") : null;
            case "state" -> kind.startsWith("ledger ") ? kind + " committed its state on " + r.thread() : null;
            case "layout" -> kind.startsWith("mutation ") ? GUI_CHANGED : null;
            case "frame" -> kind.equals("wake")
                    ? (r.frame() > 0 ? "woke pass #" + (r.frame() + 1) : "woke the main loop") : null;
            case "input" -> VERBS.contains(kind) ? "automation " + kind + " " + r.detail() : null;
            default -> null;
        };
    }

    /**
     * A {@code send} or {@code receive} row's detail, {@code <topic><by><inbox> <hops>}, said as
     * {@code <verb><type><at><inbox>}: the topic by its type's name and the inbox by its class's, when it is one; null
     * when it is not that shape.
     */
    private static String said(String detail, String by, String verb, String at) {
        int split = detail.indexOf(by);
        int end = detail.lastIndexOf(' ');
        if (split < 0 || end <= split + by.length()) {
            return null;
        }
        String inbox = detail.substring(split + by.length(), end);
        // A named mailbox follows its inbox, as class#method: the method it reached says more than the inbox does.
        int named = inbox.indexOf(' ');
        if (named > 0) {
            return verb + typeName(detail.substring(0, split)) + at + typeName(inbox.substring(named + 1))
                    + " in " + inbox.substring(0, named);
        }
        return verb + typeName(detail.substring(0, split)) + at + (isMessageType(inbox) ? typeName(inbox) : inbox);
    }

    /** Add the hop {@code says} to {@code r}'s cause's path, or count it on the last if that said the same there. */
    private static void walk(List<Hop> next, Row r, String says) {
        // In the order the hops began: a zone's row (a publish, a ledger's step) is written when it ends, after what
        // it did, so it goes back by how long it took.
        long took = r.lane().equals("layout") || r.lane().equals("frame") || r.lane().equals("draw") ? -1L
                : Durations.nanos(r.detail());
        long began = took > 0 ? r.atNanos() - took : r.atNanos();
        boolean relays = r.kind().equals("mutation layout");
        boolean redraws = r.kind().equals("mutation draw");
        int at = next.size();
        while (at > 0 && next.get(at - 1).atNanos() > began) {
            at--;
        }
        Hop before = at == 0 ? null : next.get(at - 1);
        if (before != null && before.says().equals(says) && before.thread().equals(r.thread())) {
            next.set(at - 1, before.again(relays, redraws));
        } else {
            next.add(at, new Hop(says, r.thread(), began, 1, relays ? 1 : 0, redraws ? 1 : 0, r, ""));
        }
    }

    /**
     * An event's path, in words, a hop at a time: what set its cause off, as {@link #causeOf} says it, then every hop
     * the rows of {@code rows} sharing that cause took, in their order; for a pass, ending at the pass. Input from the
     * devices has no cause of its own, so its path is the devices and the pass that read them. Empty when nothing is
     * known.
     *
     * @param rows the rows to read the hops from, in the order they were folded: the tape's
     */
    public String pathOf(Mark m, Iterable<Row> rows) {
        return pathFrom(m, hopsOf(m.cause(), rows));
    }

    /** As {@link #pathOf(Mark, Iterable)}, from the hops {@link #hopsOf} read of the event's cause. */
    public String pathFrom(Mark m, List<Hop> hops) {
        if (m.kind().equals(INPUT_TAKEN)) {
            return "the devices" + (m.frame() > 0 ? " → read in pass #" + m.frame() : "");
        }
        List<String> said = new ArrayList<>();
        String origin = causeOf(m.cause(), names);
        if (!origin.isEmpty()) {
            said.add(origin);
        }
        for (int i = 0; i < hops.size(); i++) {
            Hop h = hops.get(i);
            // How long after the hop before it this one began: the time a click's answer took, a hop at a time.
            said.add(h.said() + (i == 0 ? "" : String.format(Locale.ROOT, " +%.2f ms",
                    (h.atNanos() - hops.get(i - 1).atNanos()) / 1e6)));
        }
        boolean woke = hops.stream().anyMatch(h -> h.says().equals("woke pass #" + m.frame()));
        if (m.kind().equals(PASS) && m.frame() > 0 && !woke) {
            said.add("pass #" + m.frame());
        }
        return String.join(" → ", said);
    }

    /**
     * What every panel marks of event {@code m}'s path, from the {@code hops} of its cause: the cause itself; the passes
     * its hops on the main thread were in, and those its wakes woke, and its own if it is a pass or the devices' input;
     * the ticks its hops were in; the draws that showed what it changed; and each topic it wrote or was delivered on.
     */
    static Traced traced(String run, Mark m, List<Hop> hops) {
        java.util.LinkedHashSet<Long> passes = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> ticks = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> draws = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<Crossed> crossed = new java.util.LinkedHashSet<>();
        if ((m.kind().equals(PASS) || m.kind().equals(INPUT_TAKEN)) && m.frame() > 0) {
            passes.add(m.frame());
        }
        for (Hop h : hops) {
            Row r = h.row();
            if (isDraw(r)) {
                draws.add(numberOf(r));
                continue;
            }
            if (r.lane().equals("frame") && r.kind().equals("wake") && r.frame() > 0) {
                passes.add(r.frame() + 1);
            } else if (r.thread().equals("main") && r.frame() > 0) {
                passes.add(r.frame());
            }
            if (!h.tick().isEmpty()) {
                ticks.add(h.tick());
            }
            Crossing c = crossing(r);
            if (c != null) {
                crossed.add(new Crossed(c.topic(), c.write() ? "" : c.who()));
            }
        }
        return new Traced(run, m.cause(), List.copyOf(passes), List.copyOf(ticks), List.copyOf(draws),
                List.copyOf(crossed));
    }

    /**
     * The hops cause {@code cause} took through {@code rows}, in the order they began: every row in it that is a hop
     * ({@link #hopOf}), each placed in the numbered tick on its thread whose zone it fell in, and each draw that says
     * it showed changes made in the cause ({@code draw.ran}), which runs in its pass's cause and not this one: a click
     * moves the pointer there, redrawing a hover on the way, before the draw that shows what it did. Empty for no
     * cause.
     *
     * @param rows the rows to read the hops from, in the order they were folded: the tape's
     */
    public static List<Hop> hopsOf(String cause, Iterable<Row> rows) {
        List<Hop> hops = new ArrayList<>();
        if (cause.isEmpty()) {
            return hops;
        }
        // Each thread's numbered ticks, from the zone and the row after it: {from, to} by number.
        Map<String, long[]> zone = new HashMap<>();
        Map<String, List<Map.Entry<String, long[]>>> ticks = new HashMap<>();
        // A minute of rows, most of them another cause's: a string's hash is kept once worked out, so it tells most apart.
        int hash = cause.hashCode();
        for (Row r : rows) {
            // On main, only the logic's: under --logic=main the logic's ticks and the widgets' are both there, each
            // numbered by its own count, and only the logic's row names it (FN-16).
            if (r.lane().equals("bus") && r.kind().equals(TICK)) {
                long took = Durations.nanos(r.detail());
                if (took >= 0) {
                    zone.put(r.thread(), new long[]{r.atNanos() - took, r.atNanos()});
                }
            } else if (r.lane().equals("bus") && r.kind().equals(TICK_RAN)) {
                long[] z = zone.remove(r.thread());
                if (z != null && isLogicTick(r)) {
                    ticks.computeIfAbsent(r.thread(), t -> new ArrayList<>()).add(Map.entry(numberOf(r), z));
                }
            } else if (isDraw(r) && shownBy(r).contains(cause)) {
                walk(hops, r, "shown by draw " + numberOf(r));
                continue;
            }
            String says = r.cause().hashCode() == hash && r.cause().equals(cause) ? hopOf(r) : null;
            if (says != null) {
                walk(hops, r, says);
            }
        }
        for (int i = 0; i < hops.size(); i++) {
            Hop h = hops.get(i);
            for (Map.Entry<String, long[]> t : ticks.getOrDefault(h.thread(), List.of())) {
                if (h.atNanos() >= t.getValue()[0] && h.atNanos() <= t.getValue()[1]) {
                    hops.set(i, h.in(t.getKey()));
                    break;
                }
            }
        }
        return hops;
    }

    /** What {@link #DREW} says of a pass that did not draw because it did not need to, or could not. */
    /** What a {@code wake} says of the pass it woke, kept until that pass is folded: its cause, which becomes theirs. */
    private static final String WOKEN_IN = "woken in";

    public static final String DREW_YES = "yes";
    public static final String DREW_UPDATED = "no, it only updated";
    public static final String DREW_NOTHING = "no, nothing had changed";
    public static final String DREW_CEILING = "no, held for the frame-rate ceiling";
    public static final String DREW_GPU = "no, held for the GPU";

    /**
     * The field a loop row fills of its pass, or null when it fills none: what woke the next, which becomes its cause
     * ({@link #WOKEN_IN}), whether it drew or why not ({@link #DREW}), and how long it parked and waited after.
     */
    private static Field aboutPass(Row r) {
        if (!r.lane().equals("frame")) {
            return null;
        }
        return switch (r.kind()) {
            case "wake" -> r.cause().isEmpty() ? null : new Field(WOKEN_IN, r.cause());
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
    private static boolean setOn(List<Appended<Mark>> held, List<List<Mark>> added, boolean[] changed, int lane,
                                 long pass, Field about) {
        List<Mark> fresh = added.get(lane) == null ? List.of() : added.get(lane);
        Appended<Mark> marks = held.get(lane);
        for (int i = fresh.size() + marks.size() - 1; i >= 0; i--) {
            boolean isFresh = i >= marks.size();
            Mark m = isFresh ? fresh.get(i - marks.size()) : marks.get(i);
            if (m.frame() < pass) {
                return false;
            }
            if (m.frame() == pass) {
                Mark set = about.name().equals(WOKEN_IN) ? m.because(about.value())
                        : m.with(about.name(), about.value(), true);
                if (isFresh) {
                    fresh.set(i - marks.size(), set);
                } else {
                    held.set(lane, marks.with(i, set));
                }
                changed[lane] = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Set what a {@code tick.ran} row says on the newest tick of lane {@code lane} on its thread, which is its own: the
     * zone is written as the tick ends, just before it. Nothing when that tick has already been let go, or numbered.
     */
    private static void setOnTick(List<Appended<Mark>> held, List<List<Mark>> added, boolean[] changed, int lane,
                                  Row r) {
        List<Mark> fresh = added.get(lane) == null ? List.of() : added.get(lane);
        Appended<Mark> marks = held.get(lane);
        for (int i = fresh.size() + marks.size() - 1; i >= 0; i--) {
            boolean isFresh = i >= marks.size();
            Mark m = isFresh ? fresh.get(i - marks.size()) : marks.get(i);
            if (!m.thread().equals(r.thread())) {
                continue;
            }
            if (!m.kind().equals(TICK) || !m.field(TICK_NUMBER).isEmpty()) {
                return;
            }
            // "#12 2 rounds 5 delivered", and "carried" when its deadline cut it short.
            // "#12 logic 2 rounds 5 delivered" when the tick is named: its figures one word on.
            String[] w = r.detail().split(" ");
            int at = tickNameOf(r).isEmpty() ? 1 : 2;
            Mark set = m.with(TICK_NUMBER, numberOf(r), false)
                    .with(ROUNDS, w.length > at ? w[at] : "", false)
                    .with(DELIVERED, w.length > at + 2 ? w[at + 2] : "", false)
                    .with(CARRIED, r.detail().endsWith(" carried") ? "yes, its deadline came first" : "", false);
            if (isFresh) {
                fresh.set(i - marks.size(), set);
            } else {
                held.set(lane, marks.with(i, set));
            }
            changed[lane] = true;
            return;
        }
    }

    /** What set off the changes a draw's row says it showed, in words, {@code ; } apart, and how many more there were. */
    private static String showed(Row r, Map<String, String> names) {
        List<String> said = new ArrayList<>();
        for (String c : shownBy(r)) {
            said.add(causeOf(c, names));
        }
        int more = r.detail().lastIndexOf(" +");
        if (more > 0) {
            said.add("and " + r.detail().substring(more + 2) + " more");
        }
        return String.join("; ", said);
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
        if (lane.equals("bus") && messageType(r) != null) {
            return LANES.indexOf(MESSAGES);
        }
        if (lane.equals("bus") && kind.startsWith(JOB_ZONE)) {
            return LANES.indexOf(JOBS);
        }
        if (lane.equals("bus") && kind.equals(TIMER)) {
            return LANES.indexOf(INPUT);
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
        if (kind.equals("edges")) {
            long edges = count(r.detail());
            return edges > 0 ? moment(r, at, INPUT_TAKEN).with(EDGES, Long.toString(edges), false) : null;
        }
        if (VERBS.contains(kind)) {
            return moment(r, at, INJECTED).with(VERB, kind, false).with(SENT, r.detail(), false);
        }
        if (kind.equals(HELD_FOR_CEILING) || kind.equals(HELD_FOR_GPU)) {
            return moment(r, at, kind).with(REASON,
                    kind.equals(HELD_FOR_CEILING) ? "the frame-rate ceiling's next slot had not come"
                            : "the GPU had not finished the last frame", false);
        }
        String type = r.lane().equals("bus") ? messageType(r) : null;
        if (type != null) {
            return moment(r, at, MESSAGE).with(TYPE, typeName(type), false).with(PACKAGE, typePackage(type), false);
        }
        if (kind.equals(TIMER)) {
            // What a timer sent, as the moment it came due: from outside the logic, as input is.
            return moment(r, at, TIMER).with(TYPE, typeName(r.detail()), false)
                    .with(PACKAGE, typePackage(r.detail()), false);
        }
        if (kind.startsWith(JOB_ZONE)) {
            long ran = Durations.nanos(r.detail());
            return ran < 0 ? null : fields(new Mark(at - ran, at, JOB, r.thread(), r.frame(), r.cause(), List.of()))
                    .with(TYPE, kind.substring(JOB_ZONE.length()), false)
                    .with(TOOK, String.format(Locale.ROOT, "%.2f ms", ran / 1e6), false);
        }
        long took = Durations.nanos(r.detail());
        if (took < 0) {
            return null;
        }
        String what = kind.equals("frame") ? PASS : kind.equals("tick") ? TICK : kind.equals("present frame") ? DRAW
                : kind;
        return fields(new Mark(at - took, at, what, r.thread(), r.frame(), r.cause(), List.of()))
                .with(TOOK, String.format(Locale.ROOT, "%.2f ms", took / 1e6), false);
    }

    /**
     * The message a bus row is of, its type's full name, or null for the bus's own rows: a publish, whose kind is the
     * type, or a component's {@code send}, whose detail begins with it ({@code <type> from <inbox> <hops>}).
     */
    private static String messageType(Row r) {
        if (isMessageType(r.kind())) {
            return r.kind();
        }
        if (r.kind().equals("send")) {
            int from = r.detail().indexOf(" from ");
            return from > 0 && isMessageType(r.detail().substring(0, from)) ? r.detail().substring(0, from) : null;
        }
        return null;
    }

    /** A moment {@code r} said, with its pass, cause and the fields every event has. */
    private static Mark moment(Row r, long at, String kind) {
        return fields(new Mark(at, at, kind, r.thread(), r.frame(), r.cause(), List.of()));
    }

    /** {@code m} with the fields every event has but its path, which grows after it: its type, pass and thread. */
    private static Mark fields(Mark m) {
        return m.with(TYPE, m.kind(), false)
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

    /** The readouts over the second before this model's moment, the rows lost counted from the first row read. */
    Readouts readouts(String run) {
        return readouts(run, newestNanos - READOUT_NANOS, newestNanos, false, lost);
    }

    /**
     * The readouts over the events of this model ending after {@code from} and at or before {@code to}, saying
     * {@code rowsLost} as lost and whether the stretch was {@code selected}.
     */
    Readouts readouts(String run, long from, long to, boolean selected, long rowsLost) {
        int draws = 0;
        int ceiling = 0;
        int gpu = 0;
        for (Mark m : lane(DRAWING).marks()) {
            if (m.toNanos() <= from || m.toNanos() > to) {
                continue;
            }
            draws += m.kind().equals(DRAW) ? 1 : 0;
            ceiling += m.kind().equals(HELD_FOR_CEILING) ? 1 : 0;
            gpu += m.kind().equals(HELD_FOR_GPU) ? 1 : 0;
        }
        long[] recordings = lane(DRAWING).marks().stream()
                .filter(m -> m.toNanos() > from && m.toNanos() <= to && m.kind().equals(DRAW))
                .mapToLong(m -> Durations.nanos(m.field(RECORDING))).filter(n -> n >= 0).sorted().toArray();
        long[] ticks = lane(LOGIC).marks().stream().filter(m -> m.toNanos() > from && m.toNanos() <= to)
                .mapToLong(m -> m.toNanos() - m.fromNanos()).toArray();
        Arrays.sort(ticks);
        return new Readouts(run, from, to, selected, draws, ceiling, gpu, percentile(recordings, 0.50),
                percentile(recordings, 0.95), percentile(recordings, 1.0), ticks.length, percentile(ticks, 0.50),
                percentile(ticks, 0.95), percentile(ticks, 1.0), rowsLost, longest(MAIN, PASS, from, to, false),
                longest(LOGIC, "", from, to, false), longest(DRAWING, DRAW, from, to, true));
    }

    /**
     * The event on {@code lane} ending after {@code from} and at or before {@code to} that took longest, the first of
     * equals: of {@code kind}, or of any kind when it is empty; by its recording when {@code byRecording}, else by its
     * length. Empty when none is in the stretch.
     */
    private Optional<Event> longest(String lane, String kind, long from, long to, boolean byRecording) {
        Mark best = null;
        long most = -1;
        for (Mark m : lane(lane).marks()) {
            if (m.toNanos() <= from || m.toNanos() > to || !(kind.isEmpty() || m.kind().equals(kind))) {
                continue;
            }
            long n = byRecording ? Durations.nanos(m.field(RECORDING)) : m.toNanos() - m.fromNanos();
            if (n > most) {
                best = m;
                most = n;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Event(lane, best));
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
