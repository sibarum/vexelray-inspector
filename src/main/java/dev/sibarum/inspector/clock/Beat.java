package dev.sibarum.inspector.clock;

/**
 * What the escapement shows at one moment: the last frame, and the rest the loop is in or last came out of. A
 * value, so the view can be handed the whole of it and never disagree with itself.
 *
 * @param frames        frames presented in this run
 * @param frame         the last frame's number as the target counted it ({@code #12}), or -1 if it did not say
 * @param frameNanos    how long the last frame took (its {@code frame} span), or -1 if no span was seen
 * @param intervalNanos from the frame before to the last one, or -1 for the first frame
 * @param resting       the loop is parked now
 * @param budget        the current or last park's budget as the target wrote it: {@code 200ms}, {@code forever}
 * @param restedNanos   how long the last finished rest lasted, or -1 if none has finished
 * @param wake          what ended the last rest ({@code mutation}, {@code injected input}), or "" if not known
 * @param waker         the thread that woke the loop, or ""
 * @param atNanos       the target's monotonic time of the row that produced this beat
 */
public record Beat(long frames, long frame, long frameNanos, long intervalNanos, boolean resting, String budget,
                   long restedNanos, String wake, String waker, long atNanos) {

    /** Before any row. */
    public static final Beat NONE = new Beat(0, -1, -1, -1, false, "", -1, "", "", 0);

    /** The park budget is unbounded: the loop sleeps until something wakes it. */
    public boolean restsForever() {
        return budget.equalsIgnoreCase("forever");
    }

    /** A zero budget: the loop parked only to look for events, and did not mean to rest. */
    public boolean restsNotAtAll() {
        return Durations.nanos(budget) == 0;
    }
}
