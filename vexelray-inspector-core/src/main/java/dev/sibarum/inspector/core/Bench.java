package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.sibarum.inspector.core.Messages.Span;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;
import sibarum.atchung.Step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The run being inspected, and everything known of it: which run, what has been read of it, and the model the panels
 * show (vexelray-inspector's {@code docs/design.md} section 8). One immutable {@link State} on a ledger; each message
 * runs one declared step, and each step publishes the {@link Timeline} the view draws.
 *
 * <p><b>Attaching.</b> The first window asks which runs there are, and the timer asks again every
 * {@link #LOOK_AGAIN}; while none is attached, the newest is. When it goes away the bench says so and attaches to
 * the next one the timer finds. (The picker, and choosing a run, are R1's later slices.)
 *
 * <p><b>The passes.</b> A pass of a run's main loop is its {@code frame} zone, recorded as one row at its end whose
 * detail is how long it took. The bench keeps the passes of the last {@link #REACH}, which is what the view's lane
 * spans; how long a reach memory allows is R1's to measure.
 */
@Component
@Hears({WindowOpened.class, Runs.class, Rows.class, Detached.class})
@Publishes({FindRuns.class, Attach.class, Timeline.class})
public final class Bench {

    /** How often the timer asks which runs there are. */
    static final Duration LOOK_AGAIN = Duration.ofSeconds(2);

    /** How far back the passes are kept: the lane's width in time. */
    static final long REACH = 10_000_000_000L;

    /** How many of each message the bench's inbox holds: rows arrive a batch per 10 ms. */
    static final int CAPACITY = 1024;

    /** What the status line says while nothing can be inspected. */
    static final String NONE = "No application to inspect. Start one with --automation=0 -Dprobe=all -Dprobe.format=csv";

    /**
     * Everything the bench knows, in one value.
     *
     * @param run    the run attached, or empty
     * @param title  its window's title, for the status line
     * @param status what the status line says
     * @param live   whether its rows are arriving
     * @param newest the newest moment its rows reach, on the machine's clock
     * @param passes its passes over the last {@link #REACH}, oldest first
     * @param gone   which run went away last, and why, kept to say beside "no application" until another is attached
     */
    public record State(String run, String title, String status, boolean live, long newest, List<Span> passes,
                        String gone) {
        public State {
            passes = List.copyOf(passes);
        }

        static State initial() {
            return new State("", "", "Looking for an application to inspect", false, 0L, List.of(), "");
        }

        Timeline timeline() {
            return new Timeline(run, status, live, newest, passes);
        }

        State withStatus(String status) {
            return new State(run, title, status, live, newest, passes, gone);
        }
    }

    private final Ledger<State> ledger;

    public Bench(Inbox inbox, Timer timer) {
        Ledger.Builder<State> b = Ledger.of(State.initial());
        Step<State, WindowOpened> begin = b.step("begin", Bench::begin);
        Step<State, Runs> runs = b.step("runs", Bench::runs);
        Step<State, Rows> rows = b.step("rows", Bench::rows);
        Step<State, Detached> detached = b.step("detached", Bench::detached);
        b.on(WindowOpened.TOPIC, begin)
                .on(Messages.RUNS_TOPIC, runs)
                .on(Messages.ROWS_TOPIC, rows)
                .on(Messages.DETACHED_TOPIC, detached);
        ledger = b.build(inbox, "bench", CAPACITY);
        timer.every(LOOK_AGAIN, Messages.FIND_RUNS_TOPIC, new FindRuns());
    }

    /** The bench's state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    // ------------------------------------------------------------------ the steps

    /** The main window exists: look for a run at once, rather than at the timer's first beat. */
    static State begin(State s, WindowOpened opened, Out out) {
        out.send(Messages.FIND_RUNS_TOPIC, new FindRuns());
        out.send(Messages.TIMELINE_TOPIC, s.timeline());
        return s;
    }

    /** Which runs there are: while none is attached, attach to the newest. */
    static State runs(State s, Runs found, Out out) {
        if (!s.run().isEmpty()) {
            return s;
        }
        if (found.runs().isEmpty()) {
            String none = found.problem().isEmpty() ? NONE : found.problem();
            String status = s.gone().isEmpty() ? none : s.gone() + ". " + none;
            if (status.equals(s.status())) {
                return s;
            }
            return say(s.withStatus(status), out);
        }
        Run newest = found.runs().getLast();
        out.send(Messages.ATTACH_TOPIC, new Attach(newest.name(), newest.stream()));
        return say(new State(newest.name(), newest.title(), "Attaching to " + named(newest.title(), newest.name()),
                false, 0L, List.of(), ""), out);
    }

    /** A batch of the run's rows: its passes folded on, the ones older than the reach let go. */
    static State rows(State s, Rows batch, Out out) {
        if (!batch.run().equals(s.run())) {
            return s;   // a late batch of a run since gone
        }
        long newest = s.newest();
        List<Span> passes = new ArrayList<>(s.passes());
        for (Row r : batch.rows()) {
            newest = Math.max(newest, r.atNanos());
            if (r.lane().equals("frame") && r.kind().equals("frame")) {
                long took = Durations.nanos(r.detail());
                if (took >= 0) {
                    passes.add(new Span(r.atNanos() - took, r.atNanos()));
                }
            }
        }
        long from = newest - REACH;
        passes.removeIf(p -> p.toNanos() < from);
        String status = (batch.lostTo() > 0 ? "Lost rows " + batch.lostFrom() + ".." + batch.lostTo() + " of "
                : "Inspecting ") + named(s.title(), s.run());
        return say(new State(s.run(), s.title(), status, true, newest, passes, ""), out);
    }

    /** The run's link has ended: say why, and let the timer's next beat find another. */
    static State detached(State s, Detached d, Out out) {
        if (!d.run().equals(s.run())) {
            return s;
        }
        String gone = named(s.title(), s.run()) + ": " + d.why();
        return say(new State("", "", gone, false, s.newest(), s.passes(), gone), out);
    }

    private static State say(State s, Out out) {
        out.send(Messages.TIMELINE_TOPIC, s.timeline());
        return s;
    }

    private static String named(String title, String run) {
        return title.isBlank() ? run : title + " (" + run + ")";
    }
}
