package dev.sibarum.inspector.rows;

import java.util.Objects;

/**
 * {@link Sources}, as {@link Sources#of} builds it.
 *
 * <p>The sink is still called by one source at a time: the old source is closed before the new one starts, and a
 * source's close means the sink sees nothing from it afterwards, bar one row in flight ({@link RowSource#close}).
 */
final class SwitchingSource implements Sources {

    private static final Listener NONE = new Listener() {
        @Override
        public void attached(RowSource now) {
        }

        @Override
        public void began(String origin) {
        }

        @Override
        public void ended(String why) {
        }
    };

    /** Volatile so a source's own thread can read it while {@link #attach} holds the lock closing that source. */
    private volatile RowSource current;
    private RowSink sink;
    private volatile Listener listener = NONE;

    SwitchingSource(RowSource initial) {
        this.current = Objects.requireNonNull(initial, "initial");
    }

    /** Who hears switches and ends. One listener, set before {@link #start}. */
    @Override
    public void listen(Listener listener) {
        this.listener = listener == null ? NONE : listener;
    }

    @Override
    public synchronized void start(RowSink sink) {
        if (this.sink != null) {
            throw new IllegalStateException("already started");
        }
        this.sink = watched(sink);
        listener.attached(current);
        current.start(this.sink);
    }

    /** Stop reading the current source and read {@code next} instead. Before {@link #start}, it replaces the first. */
    @Override
    public synchronized void attach(RowSource next) {
        Objects.requireNonNull(next, "next");
        current.close();
        current = next;
        if (sink != null) {
            listener.attached(next);
            next.start(sink);
        }
    }

    /** The source being read now. */
    @Override
    public RowSource current() {
        return current;
    }

    /** The sink, with each end of a run told to the listener as well. */
    private RowSink watched(RowSink to) {
        return new RowSink() {
            @Override
            public void begin(String origin) {
                to.begin(origin);
                listener.began(origin);
            }

            @Override
            public void row(Row row) {
                to.row(row);
            }

            @Override
            public void end(String why) {
                to.end(why);
                if (!current().resumes()) {
                    listener.ended(why);
                }
            }
        };
    }

    @Override
    public synchronized String describe() {
        return current.describe();
    }

    @Override
    public synchronized void close() {
        current.close();
    }
}
