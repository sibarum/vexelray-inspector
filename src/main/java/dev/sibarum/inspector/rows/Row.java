package dev.sibarum.inspector.rows;

/**
 * One row of a correlation log: the {@code correlations.csv} schema, {@code seq,t_mono_ns,t_wall,thread,lane,kind,detail}
 * (vexelray-gui {@code docs/reference/automation.md} §4).
 *
 * <p>{@code frame} is the one field a probe file does not have. The flight recorder's stream stamps every row with
 * the frame it happened in (flight-recorder.md §6), and a file written by Probe today carries the frame only in the
 * {@code detail} of a {@code frame.present} row ({@code #<frame>}). So it is {@link #NO_FRAME} for a row read from a
 * file, and a reader that wants it from a file asks {@link #presentedFrame()} instead.
 *
 * @param seq       the single writer's row number; gapless, so a gap is loss
 * @param monoNanos monotonic nanos since the target started: the sort key, and the only clock to trust
 * @param wall      ISO-8601 UTC, for people
 * @param thread    who
 * @param lane      where: {@code frame}, {@code bus}, {@code gpu} ...
 * @param kind      what: {@code frame.present}, {@code loop.park}, a span's name
 * @param detail    the producer's own text, unescaped
 * @param frame     the frame the row happened in, or {@link #NO_FRAME}
 */
public record Row(long seq, long monoNanos, String wall, String thread, String lane, String kind, String detail,
                  long frame) {

    /** No frame column was present. */
    public static final long NO_FRAME = -1;

    /** The loop presented a frame. One per frame, unconditionally, so silence means something. */
    public static final String FRAME_PRESENT = "frame.present";

    /** The loop went to sleep; {@code detail} is the budget ({@code 200ms}, {@code 0ms}, {@code forever}). */
    public static final String LOOP_PARK = "loop.park";

    /** Something asked for a frame; {@code detail} says what ({@code mutation on Gui@...}). */
    public static final String WAKE = "wake";

    /** The frame span: {@code detail} is how long the frame took ({@code 2.39ms}, {@code 777.4us}). */
    public static final String FRAME_SPAN = "frame";

    public boolean is(String lane, String kind) {
        return this.kind.equals(kind) && this.lane.equals(lane);
    }

    /**
     * The frame number a {@code frame.present} row names ({@code #12}), else the stamped {@link #frame}, else
     * {@link #NO_FRAME}.
     */
    public long presentedFrame() {
        if (frame != NO_FRAME) {
            return frame;
        }
        if (kind.equals(FRAME_PRESENT) && detail.startsWith("#")) {
            try {
                return Long.parseLong(detail.substring(1).trim());
            } catch (NumberFormatException e) {
                return NO_FRAME;
            }
        }
        return NO_FRAME;
    }
}
