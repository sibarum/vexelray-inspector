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
 * @param lostRows      rows the stream lost in this run, counted in the target's own numbering, which spans every
 *                      lane and not only {@code frame}; 0 if none
 * @param uncoveredNanos the part of the last judged gap that no park covers: the interval before the last frame less
 *                      the budget of the park inside it, or, when a rest outlasted its budget, the overrun as soon
 *                      as the rest ended; -1 if no gap has been judged since the run began or the stream lost rows
 * @param stalls        stalls in this run: gaps whose uncovered part was over {@link #STALL}
 */
public record Beat(long frames, long frame, long frameNanos, long intervalNanos, boolean resting, String budget,
                   long restedNanos, String wake, String waker, long atNanos, long lostRows, long uncoveredNanos,
                   long stalls) {

    /**
     * How much of a gap no park may cover before it is a stall: {@code csvview --gaps}' and {@code ottermate gaps}'
     * own default, so the three agree on what a stall is.
     */
    public static final long STALL = 100_000_000;

    /** Before any row. */
    public static final Beat NONE = new Beat(0, -1, -1, -1, false, "", -1, "", "", 0, 0, -1, 0);

    /**
     * The last gap was a stall: the loop was silent for longer than {@link #STALL} beyond any park that said it
     * would sleep (automation.md §4, <i>Reading it</i>). A gap the stream lost is never judged, so never a stall.
     */
    public boolean stalled() {
        return uncoveredNanos > STALL;
    }

    /**
     * The last frame came after a gap in the stream, so beats may have been missed before it. A gap forgets the
     * frame before it, so this is a frame with no interval in a run that has lost rows.
     */
    public boolean missedBeats() {
        return lostRows > 0 && frames > 0 && intervalNanos < 0;
    }

    /** The park budget is unbounded: the loop sleeps until something wakes it. */
    public boolean restsForever() {
        return budget.equalsIgnoreCase("forever");
    }

    /** A zero budget: the loop parked only to look for events, and did not mean to rest. */
    public boolean restsNotAtAll() {
        return Durations.nanos(budget) == 0;
    }
}
