package dev.sibarum.inspector.tape;

import dev.sibarum.inspector.rows.Row;

import java.util.HashMap;
import java.util.Map;

/**
 * The tape: every row of the last few seconds, as its header only (seq, time, thread, lane, kind, frame; never the
 * {@code detail}), so the strip can draw them and a frozen moment can be looked through (roadmap M2, <i>the tape
 * view</i>).
 *
 * <h2>How far back it reaches</h2>
 *
 * {@code --tape.seconds}, ten by default. The ring is sized for that many seconds at {@link #RATE} rows a second, and
 * never more than {@link #MOST} rows, allocated once, when the tape is made. A storm faster than that pushes the oldest
 * rows out early: {@link #held} says how far back the tape really reaches, and {@link #overwritten} how many rows went
 * before their time, so a short tape is said rather than hidden.
 *
 * <h2>Names</h2>
 *
 * Thread, lane and kind are kept as numbers into one table of names, so a row costs a few numbers and nothing is
 * allocated for it. The table holds {@link #NAMES} names; past that a name is {@link #OTHER}. The lanes are also
 * numbered in the order first seen, up to {@link #TRACKS}, one track each on the strip; the rest share the last.
 *
 * <h2>Threads</h2>
 *
 * One writer, the source's thread ({@link #begin}, {@link #row}). Readers on any thread: a row's index is published
 * by {@link #written}, and a reader that copied a row checks {@link #valid} afterwards, because the writer never waits
 * and may have reused its slot meanwhile.
 */
public final class Tape {

    /** Rows a second the reach is sized for: a full trace of a busy application. */
    public static final int RATE = 20_000;

    /** The most rows the tape holds, however long its reach: about 500 MB. */
    public static final int MOST = 1 << 24;

    /** Distinct names kept. */
    public static final int NAMES = 1024;

    /** Lanes with a track of their own. */
    public static final int TRACKS = 8;

    /** The name of every name past {@link #NAMES}. */
    public static final String OTHER = "(other)";

    private final long reach;
    private final int mask;
    private final long[] seq;
    private final long[] mono;
    private final long[] frame;
    private final short[] thread;
    private final short[] lane;
    private final short[] kind;
    private final byte[] track;

    // Written by the writer only; read by anyone, behind the volatile below.
    private final String[] names = new String[NAMES];
    private final String[] trackNames = new String[TRACKS];
    private final Map<String, Short> index = new HashMap<>();
    private final Map<String, Byte> tracks = new HashMap<>();
    private int nameCount;
    private volatile int trackCount;

    /** Rows written since the tape was made: the next row's index. */
    private volatile long written;

    /** The index of this run's first row. */
    private volatile long runFrom;

    /** Rows of this run pushed out while still within reach. */
    private volatile long overwritten;

    private final short presentKind;
    private final short frameLane;

    /** @param seconds how far back the tape reaches, at least one */
    public Tape(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("--tape.seconds=" + seconds + ": the tape reaches back at least 1 s");
        }
        reach = seconds * 1_000_000_000L;
        long wanted = Math.min(MOST, (long) seconds * RATE);
        int n = Integer.highestOneBit((int) Math.max(2, wanted - 1)) << 1;
        mask = n - 1;
        seq = new long[n];
        mono = new long[n];
        frame = new long[n];
        thread = new short[n];
        lane = new short[n];
        kind = new short[n];
        track = new byte[n];
        name(OTHER);
        presentKind = name(Row.FRAME_PRESENT);
        frameLane = name("frame");
    }

    // ------------------------------------------------------------------ the writer

    /** A run begins: the rows before it are another run's, and no longer read. */
    public void begin() {
        runFrom = written;
        overwritten = 0;
    }

    /** The next row, as its header. */
    public void row(Row row) {
        long i = written;
        int s = (int) (i & mask);
        if (i - capacity() >= runFrom && row.monoNanos() - mono[s] < reach) {
            overwritten++;
        }
        seq[s] = row.seq();
        mono[s] = row.monoNanos();
        frame[s] = row.presentedFrame();
        thread[s] = name(row.thread());
        lane[s] = name(row.lane());
        kind[s] = name(row.kind());
        track[s] = track(row.lane());
        written = i + 1;
    }

    private short name(String name) {
        Short n = index.get(name);
        if (n != null) {
            return n;
        }
        if (nameCount == NAMES) {
            return 0;
        }
        short m = (short) nameCount;
        names[m] = name;
        index.put(name, m);
        nameCount++;
        return m;
    }

    private byte track(String lane) {
        Byte t = tracks.get(lane);
        if (t != null) {
            return t;
        }
        int n = trackCount;
        if (n == TRACKS) {
            // A lane past the tracks: the last track is shared from now on, and says so.
            trackNames[TRACKS - 1] = OTHER;
            tracks.put(lane, (byte) (TRACKS - 1));
            return TRACKS - 1;
        }
        trackNames[n] = lane;
        tracks.put(lane, (byte) n);
        trackCount = n + 1;
        return (byte) n;
    }

    // ------------------------------------------------------------------ readers

    /** How far back the tape is meant to reach, in nanos. */
    public long reach() {
        return reach;
    }

    /** How many rows it holds at most. */
    public int capacity() {
        return mask + 1;
    }

    /** The index the next row will have; the rows written are those before it. */
    public long written() {
        return written;
    }

    /** The index of the oldest row still held in this run. */
    public long first() {
        return Math.max(runFrom, written - capacity());
    }

    /** Rows of this run pushed out while still within reach: a storm faster than {@link #RATE}. */
    public long overwritten() {
        return overwritten;
    }

    /** How far back, in nanos, the rows held reach from the newest: up to {@link #reach}; 0 with fewer than two. */
    public long held() {
        long w = written;
        long f = first();
        if (w - f < 2) {
            return 0;
        }
        long span = mono[(int) ((w - 1) & mask)] - mono[(int) (f & mask)];
        return Math.min(reach, Math.max(0, span));
    }

    /**
     * Whether row {@code i} is still held. Read a row's fields first and ask this after: if it says no, the writer
     * reused the slot while they were read, and they belong to a newer row.
     */
    public boolean valid(long i) {
        return i >= first() && i < written;
    }

    /** The first row at or after {@code nanos} in this run, or {@link #written} if there is none. */
    public long at(long nanos) {
        long lo = first();
        long hi = written;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (mono[(int) (mid & mask)] < nanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public long seq(long i) {
        return seq[(int) (i & mask)];
    }

    public long nanos(long i) {
        return mono[(int) (i & mask)];
    }

    /** The frame the row happened in, or {@link Row#NO_FRAME}. */
    public long frame(long i) {
        return frame[(int) (i & mask)];
    }

    public String thread(long i) {
        return names[thread[(int) (i & mask)]];
    }

    public String lane(long i) {
        return names[lane[(int) (i & mask)]];
    }

    public String kind(long i) {
        return names[kind[(int) (i & mask)]];
    }

    /** The row's track on the strip: its lane's, in the order lanes were first seen. */
    public int track(long i) {
        return track[(int) (i & mask)];
    }

    /** Whether the row is a {@code frame.present}: a frame's ruled line. */
    public boolean present(long i) {
        int s = (int) (i & mask);
        return kind[s] == presentKind && lane[s] == frameLane;
    }

    /**
     * Count this run's rows in {@code [from, to)} into {@code bins} equal slices of that time, stopping at
     * {@code until} (the time shown, inside a slice still being shown): {@code counts[t * bins + b]} for track
     * {@code t}, and the frames presented in {@code counts[TRACKS * bins + b]}. {@code counts} has at least
     * {@code (TRACKS + 1) * bins} entries and is cleared first. Whether every row counted was still held when it was
     * read; when not, the writer overtook the read and the counts are not to be trusted.
     */
    public boolean bin(long from, long to, long until, int bins, int[] counts) {
        java.util.Arrays.fill(counts, 0, (TRACKS + 1) * bins, 0);
        long start = at(from);
        long end = written;
        long width = Math.max(1, to - from);
        long stop = Math.min(to, until);
        for (long i = start; i < end; i++) {
            int s = (int) (i & mask);
            long t = mono[s];
            if (t >= stop) {
                break;
            }
            int b = (int) Math.min(bins - 1, Math.max(0, (t - from) * bins / width));
            counts[track[s] * bins + b]++;
            if (kind[s] == presentKind && lane[s] == frameLane) {
                counts[TRACKS * bins + b]++;
            }
        }
        return start >= first();
    }

    /** Tracks named so far. */
    public int tracks() {
        return trackCount;
    }

    /** Track {@code t}'s lane, or {@link #OTHER} for the last when lanes outnumber tracks. */
    public String trackName(int t) {
        return trackNames[t];
    }
}
