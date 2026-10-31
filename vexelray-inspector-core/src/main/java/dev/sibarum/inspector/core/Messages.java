package dev.sibarum.inspector.core;

import sibarum.atchung.Topic;

import java.util.List;
import java.util.Optional;

/**
 * Every message the application's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is four kinds of part, and none of them holds another. The {@link Bench} decides; it owns what is
 * known of the run being inspected, and changes it only by running its own declared steps. The view, in the
 * {@code -gui} module, shows; it owns the widgets, and declares the chords that send the person's keys to the bench
 * ({@link Pause}, {@link Step}). {@link ListRuns}, a job, reads the discovery folder on the job lane;
 * {@link LinkRows}, a source, reads a run's rows over its link on a thread of its own for as long as it is attached.
 * Each sends a record on a topic and the others hear it on their own lane, so no state is shared and nothing needs a
 * lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). A list may cross when what it holds may, and the list itself must not change after it is sent,
 * which no type can show: so each record that holds one copies it in its constructor, and no sender can get that
 * wrong.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside its topic. A channel is its record's type: each topic is named by its
 * payload ({@code Topic.edge(Runs.class)}), and every part refers to it by the type, a {@code @Subscribe} method's
 * parameter, a {@code Sender<Attach>}, a job's {@code run}, a class literal in {@code @Hears}. There is no topic name
 * anywhere to misspell, and a record with no topic here is a compile error wherever it is heard or sent (T4.1).
 *
 * <h2>Time</h2>
 *
 * <p>Every time here is a moment on the machine's monotonic clock, {@code System.nanoTime()}'s, in nanoseconds. A run's
 * rows count from its probe's own start, and {@link LinkRows} adds that start (the link says it) before a row crosses,
 * so the bench and the view never see a run's private zero: the view sets a row against its frame's
 * {@code monoNanos}, which is the same clock on the same machine.
 */
public final class Messages {

    private Messages() {
    }

    // ------------------------------------------------------------------ the bench, its job and its source

    /** Which applications are running now ({@link ListRuns}): asked on the first window, then on the timer's beat. */
    public static final Topic<FindRuns> FIND_RUNS_TOPIC = Topic.edge(FindRuns.class);

    public record FindRuns() {
    }

    /** One running application that can be inspected: its discovery file's base name and where its link listens. */
    public record Run(String name, String title, long pid, String stream) {
    }

    /** The applications running now with a link, oldest first, this one left out; {@code problem} when unreadable. */
    public static final Topic<Runs> RUNS_TOPIC = Topic.edge(Runs.class);

    public record Runs(List<Run> runs, String problem) {
        public Runs {
            runs = List.copyOf(runs);
        }
    }

    /** Read {@code run}'s rows over its link at {@code stream} ({@link LinkRows}), one run of the source per name. */
    public static final Topic<Attach> ATTACH_TOPIC = Topic.edge(Attach.class, Attach::run);

    public record Attach(String run, String stream) {
    }

    /** A run's link has ended: why, in words for the status line. */
    public static final Topic<Detached> DETACHED_TOPIC = Topic.edge(Detached.class);

    public record Detached(String run, String why) {
    }

    /**
     * One probe row of a run, its time on the machine's monotonic clock, and what the run's wall clock said when it was
     * written, in nanoseconds since the epoch; 0 when it did not say.
     *
     * @param frame the main loop's pass in progress when it was written, its number; 0 when the run did not say
     * @param cause what set its work off, as the probe writes a cause ({@code 3f2a1.t07.4f2}); empty when nothing
     *              did, or the run did not say
     */
    public record Row(long seq, long atNanos, long wallNanos, long frame, String thread, String lane, String kind,
                      String detail, String cause) {

        /** A row whose pass and cause are not known. */
        public Row(long seq, long atNanos, long wallNanos, String thread, String lane, String kind, String detail) {
            this(seq, atNanos, wallNanos, 0L, thread, lane, kind, detail, "");
        }

        /** A row whose wall clock, pass and cause are not known. */
        public Row(long seq, long atNanos, String thread, String lane, String kind, String detail) {
            this(seq, atNanos, 0L, thread, lane, kind, detail);
        }
    }

    /**
     * The rows of {@code run} that arrived since the last batch; {@code lostFrom..lostTo} never arrived, before them.
     * {@link LinkRows} also says the loss as a row of its own ({@link Model#LOST}), so that it is folded and kept with
     * the rest and the readouts can count it as of any moment.
     */
    public static final Topic<Rows> ROWS_TOPIC = Topic.edge(Rows.class);

    public record Rows(String run, long lostFrom, long lostTo, List<Row> rows) {
        public Rows {
            rows = List.copyOf(rows);
        }
    }

    // ------------------------------------------------------------------ the person tells the bench

    /**
     * Space: following, stop the timeline at {@code restNanos}; stopped, follow the present again. The bench decides
     * which. The moment is the view's: where its lane comes to rest if it slows from the present now, which only it can
     * know, so that a pause never moves the lane backwards (design.md 9.8). It may be a little ahead of the newest row.
     */
    public static final Topic<Pause> PAUSE_TOPIC = Topic.edge(Pause.class);

    public record Pause(long restNanos) {
    }

    /**
     * End, or the button beside the readouts: follow the present again, leaving the island the cursor was kept on.
     * Following already, nothing. Not {@link Pause}, which toggles: a button pressed while following must not stop it.
     */
    public static final Topic<Follow> FOLLOW_TOPIC = Topic.edge(Follow.class);

    public record Follow() {
    }

    /**
     * An arrow: move the cursor by {@code byNanos}, back when negative. Stopped, from the cursor; following, from
     * {@code fromNanos}, where the view's lane would come to rest. An edge, not a state topic: a step is relative, so
     * two quick presses must be two steps, not the newer one.
     */
    public static final Topic<Step> STEP_TOPIC = Topic.edge(Step.class);

    public record Step(long byNanos, long fromNanos) {
    }

    /**
     * An arrow: move the cursor {@code by} events of {@code lane}, back when negative: onto its next or previous event,
     * which it is then on. The lane is the one the view has focused. Stopped, from the cursor; following, from
     * {@code fromNanos}, where the view's lane would come to rest, so the first step back is onto the lane's newest
     * event. An edge, as {@link Step} is.
     */
    public static final Topic<StepEvent> STEP_EVENT_TOPIC = Topic.edge(StepEvent.class);

    public record StepEvent(String lane, int by, long fromNanos) {
    }

    /**
     * A click where there is no event near: put the cursor at {@code atNanos}, pausing if following; never off the
     * island the bench keeps while paused. The absolute cursor (design.md section 8): a state topic,
     * so a drag through a storm of moments costs the bench one a tick, the newest winning.
     */
    public static final Topic<Seek> SEEK_TOPIC = Topic.state(Seek.class);

    public record Seek(long atNanos) {
    }

    /** A click on an event: put the cursor on it, pausing if following. Ignored unless it is one the tape holds. */
    public static final Topic<GoTo> GO_TO_TOPIC = Topic.edge(GoTo.class);

    public record GoTo(Event event) {
    }

    // ------------------------------------------------------------------ the bench tells the view

    /**
     * One event on a lane, on the machine's monotonic clock: a stretch from its start to its end (a pass, a tick, a
     * draw, a wait), or a moment when the two are equal (an input, a draw held). {@code kind} says which, in words the
     * status line can use, and is one of {@link Model}'s kinds.
     *
     * @param thread  the thread whose row said it, as the probe names it; empty when not known
     * @param what    what its own row said of it, in the run's words: a message's type, its full class name; what an
     *                automation verb injected ({@code key SPACE}); how many edges the devices gave; empty when its row
     *                says nothing more than its kind and length
     * @param frame   the main loop's pass it happened in, its number; 0 when the run did not say
     * @param cause   what set it off, as the probe writes a cause ({@code 3f2a1.t07.4f2}); empty when not known
     * @param context what the rows around it say of it, in words: for a pass, what woke it, whether it drew or why
     *                not, and how long it parked; empty when they say nothing
     */
    public record Mark(long fromNanos, long toNanos, String kind, String thread, String what, long frame, String cause,
                       String context) {

        /** An event whose thread is not known and whose rows say nothing more of it. */
        public Mark(long fromNanos, long toNanos, String kind) {
            this(fromNanos, toNanos, kind, "", "");
        }

        /** An event whose pass, cause and context are not known. */
        public Mark(long fromNanos, long toNanos, String kind, String thread, String what) {
            this(fromNanos, toNanos, kind, thread, what, 0L, "", "");
        }

        /** This event, with {@code more} said of it after what its context already says. */
        public Mark said(String more) {
            if (more.isEmpty()) {
                return this;
            }
            return new Mark(fromNanos, toNanos, kind, thread, what, frame, cause,
                    context.isEmpty() ? more : context + "; " + more);
        }
    }

    /** One lane of the timeline: a clock's events, in the order their rows arrived. */
    public record Lane(String name, List<Mark> marks) {
        public Lane {
            marks = List.copyOf(marks);
        }
    }

    /** An event, and the lane it is on. */
    public record Event(String lane, Mark mark) {
    }

    /**
     * The timeline: which run, what to say about it, where the cursor is, and every event kept on every lane, in
     * {@link Model#LANES}' order. A state topic: a storm of rows costs the view one a frame.
     *
     * <p>The events are all those kept, not only those before the cursor: the lanes glide between moments (design.md
     * 9.8) and show what they pass on the way, and at rest the cursor may be anywhere across them, with what came after
     * it to its right.
     *
     * @param run       the run attached, or empty when none is
     * @param status    what the status line says
     * @param live      whether the timeline follows the present: rows are arriving and the cursor is not stopped
     * @param edgeNanos the newest row while following; the cursor while stopped, which the view shows as a line and keeps
 *                  in view, its lanes at rest wherever it chooses (design.md 9.8)
     * @param on        the event the cursor is on, after a step by events or a click; the cursor is then at its end
     */
    public static final Topic<Timeline> TIMELINE_TOPIC = Topic.state(Timeline.class);

    public record Timeline(String run, String status, boolean live, long edgeNanos, List<Lane> lanes,
                           Optional<Event> on) {
        public Timeline {
            lanes = List.copyOf(lanes);
        }
    }

    /**
     * The readouts, over the second before the cursor (the newest row while following), from the same model the
     * timeline is drawn from, so they rewind with it. A state topic, as the timeline is.
     *
     * <p>Ticks are those of the logic lane's own thread: under {@code --logic=main} they run on the main thread beside
     * the widgets' tick, which no row tells apart yet (framework-notes FN-8), so there they read nothing.
     *
     * @param run            the run attached, or empty when none is
     * @param draws          draws in the second
     * @param heldForCeiling draws held for the ceiling in it: pacing doing its job
     * @param heldForGpu     draws held for the GPU in it: the GPU being slow
     * @param ticks          logic ticks in it
     * @param tickP50Nanos   how long they took, the median; 0 with none
     * @param tickP95Nanos   the 95th percentile; 0 with none
     * @param rowsLost       rows the link lost, from the first row read to the cursor
     */
    public static final Topic<Readouts> READOUTS_TOPIC = Topic.state(Readouts.class);

    public record Readouts(String run, int draws, int heldForCeiling, int heldForGpu, int ticks, long tickP50Nanos,
                           long tickP95Nanos, long rowsLost) {
    }

    /**
     * What is known about the cursor's moment and the event it is on, for the details panel: what the inspected run's
     * clock said then, how far into the run it was, and the event. Who received a message, how it travelled and which
     * component sent it are not known from the rows yet (framework-notes FN-10), so they are not fields here. A state
     * topic, as the timeline is.
     *
     * @param run         the run attached, or empty when none is
     * @param atNanos     the cursor's moment, the newest row's while following, on the machine's monotonic clock
     * @param intoNanos   how far into the run that is, from its first row read
     * @param clockNanos  what the run's wall clock said at that moment, in nanoseconds since the epoch; -1 when no row
     *                    has said
     * @param on          the event the cursor is on, if any
     * @param causedBy    what set that event off, in words, from the sources the run named ({@link Model#causeOf});
     *                    empty when there is no event or the run did not say
     */
    public static final Topic<Details> DETAILS_TOPIC = Topic.state(Details.class);

    public record Details(String run, long atNanos, long intoNanos, long clockNanos, Optional<Event> on,
                          String causedBy) {

        /** Details whose event's cause is not known. */
        public Details(String run, long atNanos, long intoNanos, long clockNanos, Optional<Event> on) {
            this(run, atNanos, intoNanos, clockNanos, on, "");
        }
    }
}
