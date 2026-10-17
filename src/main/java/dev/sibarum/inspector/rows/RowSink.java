package dev.sibarum.inspector.rows;

/**
 * Where a {@link RowSource} delivers. Called on the source's own thread, one call at a time, in time order.
 */
public interface RowSink {

    /**
     * A run of rows starts: the first, or a replay starting over. Whatever was derived from earlier rows describes
     * another run now, so a sink resets here. {@code origin} says where the rows come from, for people.
     */
    void begin(String origin);

    /** The next row. */
    void row(Row row);

    /** No more rows will come from this run; {@code why} says why, for people. A looping replay begins again. */
    void end(String why);
}
