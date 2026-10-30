package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;

import java.time.Duration;
import java.util.Locale;

/**
 * The run being inspected, and everything known of it: which run, what has been read of it, and the model the panels
 * show (vexelray-inspector's {@code docs/design.md} section 8). One immutable {@link State} on a ledger; each message
 * runs one declared step, and each step that changes what is shown publishes the {@link Timeline} the view draws.
 *
 * <p><b>Attaching.</b> The first window asks which runs there are, and the timer asks again every
 * {@link #LOOK_AGAIN}; while none is attached, the newest is. When it goes away the bench says so and attaches to
 * the next one the timer finds. (The picker, and choosing a run, are R1's later slices.)
 *
 * <p><b>The model, live and as of the cursor.</b> Each batch is folded onto the live {@link Model} and kept on the
 * {@link Tape}, a second at a time beside a checkpoint, for the last {@link #RETAIN}. Following, the bench shows the
 * live model. Paused ({@link Pause}, {@link Step}), it shows the model as of the cursor, recomputed from the tape by
 * the same fold, so it is exactly what was shown at that moment. Rows go on arriving and being kept while paused; only
 * what is shown stays put. A run that goes away keeps its tape, to step through, until another is attached.
 */
@Component
@Hears({WindowOpened.class, Runs.class, Rows.class, Detached.class, Pause.class, Step.class})
@Publishes({FindRuns.class, Attach.class, Timeline.class})
public final class Bench {

    /** How often the timer asks which runs there are. */
    static final Duration LOOK_AGAIN = Duration.ofSeconds(2);

    /** How far back a model's passes reach: the lane's width in time. */
    static final long REACH = 10_000_000_000L;

    /** How far back the tape keeps rows: as far as the cursor can go. How long memory allows is R1's to measure. */
    static final long RETAIN = 60_000_000_000L;

    /** How many of each message the bench's inbox holds: rows arrive a batch per 10 ms. */
    static final int CAPACITY = 1024;

    /** What the status line says while nothing can be inspected. */
    static final String NONE = "No application to inspect. Start one with --automation=0 -Dprobe=all -Dprobe.format=csv";

    /**
     * Everything the bench knows, in one value.
     *
     * @param run      the run attached, or empty
     * @param title    its window's title, for the status line
     * @param status   what the status line says
     * @param arriving whether its rows are arriving
     * @param now      the model with every row read so far folded on
     * @param tape     the rows of the last {@link #RETAIN}, with their checkpoints
     * @param first    the moment of the run's first row read, which the status line counts from
     * @param paused   whether the cursor is stopped; following the present when not
     * @param cursor   where it is stopped, on the machine's clock
     * @param gone     which run went away last, and why, kept to say beside "no application" until another is attached
     */
    public record State(String run, String title, String status, boolean arriving, Model now, Tape tape, long first,
                        boolean paused, long cursor, String gone) {

        static State initial() {
            return new State("", "", "Looking for an application to inspect", false, Model.EMPTY, Tape.EMPTY, 0L,
                    false, 0L, "");
        }

        /** The timeline as of the cursor: the live model while following, the tape's as of the cursor when paused. */
        Timeline timeline() {
            if (paused) {
                return new Timeline(run, status, false, cursor, tape.asOf(cursor).passes());
            }
            return new Timeline(run, status, arriving, now.newestNanos(), now.passes());
        }

        State withStatus(String status) {
            return new State(run, title, status, arriving, now, tape, first, paused, cursor, gone);
        }

        State following(String status) {
            return new State(run, title, status, arriving, now, tape, first, false, 0L, gone);
        }

        State pausedAt(long at, String status) {
            return new State(run, title, status, arriving, now, tape, first, true, at, gone);
        }
    }

    private final Ledger<State> ledger;

    public Bench(Inbox inbox, Timer timer) {
        Ledger.Builder<State> b = Ledger.of(State.initial());
        b.on(WindowOpened.TOPIC, b.step("begin", Bench::begin))
                .on(Messages.RUNS_TOPIC, b.step("runs", Bench::runs))
                .on(Messages.ROWS_TOPIC, b.step("rows", Bench::rows))
                .on(Messages.DETACHED_TOPIC, b.step("detached", Bench::detached))
                .on(Messages.PAUSE_TOPIC, b.step("pause", Bench::pause))
                .on(Messages.STEP_TOPIC, b.step("step", Bench::step));
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

    /** Which runs there are: while none is attached, attach to the newest, and let the last one's tape go. */
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
                false, Model.EMPTY, Tape.EMPTY, 0L, false, 0L, ""), out);
    }

    /** A batch of the run's rows: folded onto the live model and kept; shown at once only while following. */
    static State rows(State s, Rows batch, Out out) {
        if (!batch.run().equals(s.run())) {
            return s;   // a late batch of a run since gone
        }
        Model now = s.now().fold(batch.rows(), Long.MAX_VALUE);
        Tape tape = s.tape().add(batch.rows(), now.newestNanos(), RETAIN);
        long first = s.tape().isEmpty() && !batch.rows().isEmpty() ? batch.rows().getFirst().atNanos() : s.first();
        if (s.paused()) {
            // Nothing shown changes: the cursor stays where it was, and the rows are kept for when it moves.
            return new State(s.run(), s.title(), s.status(), true, now, tape, first, true, s.cursor(), "");
        }
        String status = (batch.lostTo() > 0 ? "Lost rows " + batch.lostFrom() + ".." + batch.lostTo() + " of "
                : "Inspecting ") + named(s.title(), s.run());
        return say(new State(s.run(), s.title(), status, true, now, tape, first, false, 0L, ""), out);
    }

    /** The run's link has ended: say why, keep its tape to step through, and let the timer's next beat find another. */
    static State detached(State s, Detached d, Out out) {
        if (!d.run().equals(s.run())) {
            return s;
        }
        String gone = named(s.title(), s.run()) + ": " + d.why();
        return say(new State("", "", gone, false, s.now(), s.tape(), s.first(), s.paused(), s.cursor(), gone), out);
    }

    /** Space: following, stop at the newest row; paused, follow the present again. */
    static State pause(State s, Pause p, Out out) {
        if (s.tape().isEmpty()) {
            return s;
        }
        if (s.paused()) {
            return say(s.following(following(s)), out);
        }
        return say(paused(s, s.now().newestNanos()), out);
    }

    /** An arrow: move the cursor, stopping first if following, and never past what the tape holds. */
    static State step(State s, Step by, Out out) {
        if (s.tape().isEmpty()) {
            return s;
        }
        long from = s.paused() ? s.cursor() : s.now().newestNanos();
        long at = Math.clamp(from + by.byNanos(), s.tape().oldestNanos(), s.now().newestNanos());
        return say(paused(s, at), out);
    }

    // ------------------------------------------------------------------ what the status line says

    private static State paused(State s, long at) {
        String where = String.format(Locale.ROOT, "Paused at %.2f s", (at - s.first()) / 1e9)
                + (s.run().isEmpty() ? "" : " of " + named(s.title(), s.run()))
                + (at == s.tape().oldestNanos() ? ", the oldest moment kept" : "");
        return s.pausedAt(at, s.run().isEmpty() ? s.gone() + ". " + where : where);
    }

    private static String following(State s) {
        if (s.run().isEmpty()) {
            return s.gone();
        }
        return (s.arriving() ? "Inspecting " : "Attaching to ") + named(s.title(), s.run());
    }

    private static State say(State s, Out out) {
        out.send(Messages.TIMELINE_TOPIC, s.timeline());
        return s;
    }

    private static String named(String title, String run) {
        return title.isBlank() ? run : title + " (" + run + ")";
    }
}
