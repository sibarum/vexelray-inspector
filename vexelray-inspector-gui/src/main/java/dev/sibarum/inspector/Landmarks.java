package dev.sibarum.inspector;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a driver
 * waits for the application to be ready, and it needs no hook when readiness is declared somewhere the read-model
 * can already see. {@link #STATUS_RUN} works that way: it says which run is inspected, so
 * {@code await status.run Inspecting} waits for the first rows to arrive.
 */
final class Landmarks {

    /** The title bar's wordmark: INSPECT, the gear, R ({@link Wordmark}). */
    static final String WORDMARK = "wordmark";

    /**
     * The run panel over the lanes, and in it: what is inspected and from where ({@code run.text}, which says
     * {@code attached}, {@code launched} or {@code replayed} once one is), the picker of running applications, and
     * the button that stops inspecting.
     */
    static final String RUN = "run";
    static final String RUN_TEXT = "run.text";
    static final String RUNS = "runs";
    static final String DETACH = "detach";

    /**
     * The flows table, a row a topic, and the line over it that says when it counts a stretch selected, and why there
     * is no graph when there is none.
     */
    static final String FLOWS = "flows";

    /** The rows panel: the raw rows around the cursor, the cursor's row in the accent. */
    static final String ROWS = "rows";
    static final String ROWS_BODY = "rows.body";

    /** The threads panel: a line a clock, the turn of it the cursor is in, cut into the four states. */
    static final String THREADS = "threads";
    /** The Run again button, beside Detach (R6). */
    static final String RUN_AGAIN = "run.again";
    /** The replay panel: what running again found, and the table of the sends that differ. */
    static final String REPLAY = "replay";
    static final String REPLAY_TEXT = "replay.text";
    static final String REPLAY_BODY = "replay.body";
    static final String FLOWS_WHY = "flows.why";
    /** The bodies of the flows and threads tables, whose rows a script right-clicks for Copy. */
    static final String FLOWS_BODY = "flows.body";
    static final String THREADS_BODY = "threads.body";

    /**
     * Copy: every table's row menu item, and the key binding's id (Ctrl+C), which copies the selected rows of the table
     * last clicked as CSV, a header line first.
     */
    static final String COPY = "table.copy";

    /**
     * Export frame: the run panel's button, and the key binding's id (Ctrl+E), which saves the panels as of the cursor
     * and the rows of its frame to one CSV file.
     */
    static final String EXPORT = "frame.export";

    /** The graph panel: the declared parts and the arrows between them, drawn. */
    static final String GRAPH = "graph";

    /** Over the graph panel: opens the graph window, as G does. */
    static final String GRAPH_OPEN = "graph.open";

    /**
     * In the graph window: the stage the graph is drawn on, zoomed and moved. A script reaches it as
     * {@code graph.graph.stage}, the window's key before it.
     */
    static final String GRAPH_STAGE = "graph.stage";

    /** The timeline: its lanes, the present at their right edge. */
    static final String TIMELINE = "timeline";

    /** The lanes, one band a clock: a click on one focuses it and goes to the event nearest it. */
    static final String LANES = "lanes";

    /** The details panel, under the lanes. */
    static final String DETAILS = "details";

    /**
     * One value in it: this, then its label in lower case with a dash for each space ({@code detail.clock-time},
     * {@code detail.schema}); see {@link #detail}.
     */
    static final String DETAIL = "detail.";

    /** The readouts line, over the second before the cursor, or over the stretch selected ("Selected ..."). */
    static final String READOUTS = "readouts";

    /**
     * The buttons at the readouts line's right end that go to a stretch's longest main-thread pass, logic tick and draw
     * (by its recording); enabled while a stretch is selected.
     */
    static final String LONGEST_PASS = "longest.pass";
    static final String LONGEST_TICK = "longest.tick";
    static final String LONGEST_DRAW = "longest.draw";

    /** The button at the focused lane's right end that follows the present again, as End does. */
    static final String FOLLOW = "follow";

    /**
     * The banner over the lanes saying what makes them less than whole (a run of another link version), and its text;
     * hidden while nothing does.
     */
    static final String WARNING = "warning";
    static final String WARNING_TEXT = "warning.text";

    /** The status line, and its slot saying which run is inspected or why none is. */
    static final String STATUS = "status";
    static final String STATUS_RUN = "status.run";

    /** The status line's slot naming the lanes the run's probe does not record, empty while it records them all. */
    static final String STATUS_LANES = "status.lanes";

    /** The status line's slot saying what the lanes could not draw as they should, empty while they can. */
    static final String STATUS_DRAWING = "status.drawing";

    /** The landmark of the details panel's value labelled {@code label}. */
    static String detail(String label) {
        return DETAIL + label.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }

    private Landmarks() {
    }
}
