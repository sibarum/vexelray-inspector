package dev.sibarum.inspector.rows;

/**
 * Where the Inspector's rows come from: everything it draws is derived from a sequence of rows in the
 * correlation-log format ({@link Row}), and this is the one seam between that sequence and its origin.
 *
 * <h2>The sources</h2>
 *
 * <ul>
 *   <li><b>{@link ReplaySource}</b>, today: a probe CSV file ({@code -Dprobe=all -Dprobe.format=csv}), played at the
 *       pace it was recorded at. A recording is a saved stream, so this is also how a saved session will open.</li>
 *   <li><b>The live stream</b>, next, and not written yet: the automation socket's {@code stream <lanes|all>} verb
 *       (vexelray-gui {@code docs/plans/flight-recorder.md} §6). It sends a header row and then the same rows with a
 *       {@code frame} column, which {@link RowFormat} already reads by name. A {@code StreamSource} connects (or
 *       launches the target, as {@code ottermate --launch} does), sends {@code stream all}, and feeds each line it
 *       reads to a {@link RowFormat} and on to the sink, exactly as {@link ReplaySource} does with a file's lines,
 *       minus the pacing: live rows arrive at their own pace. Its {@code lost} rows (a slow reader's dropped seq
 *       range) arrive as ordinary rows. Nothing downstream of this interface changes.</li>
 * </ul>
 *
 * <p>A source owns its thread. {@link #start} returns at once, and the sink is called from that thread until
 * {@link #close}.
 */
public interface RowSource extends AutoCloseable {

    /** Start delivering to {@code sink}. Once. */
    void start(RowSink sink);

    /** What this source is, for people: a file name, a host and port. */
    String describe();

    /**
     * Whether an end of a run is followed by a new run on its own, as a looping replay's is. The window shows the
     * picker after an end only when nothing will begin again by itself.
     */
    default boolean resumes() {
        return false;
    }

    /** Stop delivering. The sink may see one more row in flight; it never sees one after this returns. */
    @Override
    void close();

    /** No source: the Inspector was started with nothing to inspect. */
    static RowSource none(String why) {
        return new RowSource() {
            @Override
            public void start(RowSink sink) {
                sink.end(why);
            }

            @Override
            public String describe() {
                return "no source";
            }

            @Override
            public void close() {
            }
        };
    }
}
