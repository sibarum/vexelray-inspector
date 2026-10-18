package dev.sibarum.inspector.rows;

/**
 * The source the Inspector is reading now, and the one place it changes: the row source the rest of the application
 * sees, standing in front of whichever real one is current.
 *
 * <p>{@link #attach} closes the current source and starts the next on the same sink. The next one begins its own run
 * ({@link RowSink#begin}), so the escapement resets there as it does when a replay loops; nothing downstream knows a
 * switch happened. A {@link Listener} hears each switch, each run beginning, and each end after which nothing will
 * begin again by itself: that is how the window knows to show the picker when nothing is flowing and the movement when
 * something is.
 */
public interface Sources extends RowSource {

    /** Told of each switch, each beginning, and each final end. On the switching thread, or a source's thread. */
    interface Listener {

        /** {@code now} is the source being read from here on. */
        void attached(RowSource now);

        /** A run began: rows are flowing. */
        void began(String origin);

        /**
         * The current source's run ended, and {@code why}, and it will not begin again by itself
         * ({@link RowSource#resumes}): nothing flows until another source attaches.
         */
        void ended(String why);
    }

    /** Who hears switches, beginnings and ends. One listener, set before {@link #start}. */
    void listen(Listener listener);

    /** Stop reading the current source and read {@code next} instead. Before {@link #start}, it replaces the first. */
    void attach(RowSource next);

    /** The source being read now. */
    RowSource current();

    /** Starting with {@code initial}. */
    static Sources of(RowSource initial) {
        return new SwitchingSource(initial);
    }
}
