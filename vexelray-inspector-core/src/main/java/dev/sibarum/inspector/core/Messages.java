package dev.sibarum.inspector.core;

import sibarum.atchung.Topic;

import java.util.List;

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

    /** One probe row of a run, its time on the machine's monotonic clock. */
    public record Row(long seq, long atNanos, String thread, String lane, String kind, String detail) {
    }

    /** The rows of {@code run} that arrived since the last batch; {@code lostFrom..lostTo} never arrived, before them. */
    public static final Topic<Rows> ROWS_TOPIC = Topic.edge(Rows.class);

    public record Rows(String run, long lostFrom, long lostTo, List<Row> rows) {
        public Rows {
            rows = List.copyOf(rows);
        }
    }

    // ------------------------------------------------------------------ the person tells the bench

    /**
     * Space: stop the timeline at the newest row, or, stopped, follow the present again. The bench decides which, so
     * the chord sends the same message either way.
     */
    public static final Topic<Pause> PAUSE_TOPIC = Topic.edge(Pause.class);

    public record Pause() {
    }

    /**
     * An arrow: move the cursor by {@code byNanos}, back when negative; following, it stops at the newest row first.
     * An edge, not a state topic: a step is relative, so two quick presses must be two steps, not the newer one.
     */
    public static final Topic<Step> STEP_TOPIC = Topic.edge(Step.class);

    public record Step(long byNanos) {
    }

    // ------------------------------------------------------------------ the bench tells the view

    /** A stretch of time on the machine's monotonic clock: one pass of a run's main loop. */
    public record Span(long fromNanos, long toNanos) {
    }

    /**
     * The timeline as of the cursor: which run, what to say about it, the moment at the lane's right edge, and the
     * passes as they stood then, oldest first. A state topic: a storm of rows costs the view one a frame.
     *
     * @param run       the run attached, or empty when none is
     * @param status    what the status line says
     * @param live      whether the timeline follows the present: rows are arriving and the cursor is not stopped
     * @param edgeNanos the moment at the lane's right edge: the newest row while following, the cursor while stopped
     */
    public static final Topic<Timeline> TIMELINE_TOPIC = Topic.state(Timeline.class);

    public record Timeline(String run, String status, boolean live, long edgeNanos, List<Span> passes) {
        public Timeline {
            passes = List.copyOf(passes);
        }
    }
}
