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
 * {@code --tape.seconds} to begin with, ten by default, and {@link #reach(int)} from the window after. The ring is sized
 * for that many seconds at {@link #RATE} rows a second, and never more than {@link #MOST} rows. A storm faster than
 * that pushes the oldest rows out early: {@link #held} says how far back the tape really reaches, and
 * {@link #overwritten} how many rows went before their time, so a short tape is said rather than hidden.
 *
 * <p>A new reach is the reach at once. The ring it needs is made by the writer, at the next row, holding the rows the
 * old one held as far as they fit; so a longer reach starts with what the tape already had and fills from there, and a
 * shorter one lets the oldest go.
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
 * and may have reused its slot meanwhile. A row keeps its index in every ring, so a reader that reads one row from
 * the ring before a resize and the next from the ring after it reads the same rows.
 */
public final class Tape {

    /** Rows a second the reach is sized for: a full trace of a busy application. */
    public static final int RATE = 20_000;

    /** The most rows the tape holds, however long its reach: about 300 MB, and as much again for its frozen copy. */
    public static final int MOST = 1 << 23;

    /** Distinct names kept. */
    public static final int NAMES = 1024;

    /** Lanes with a track of their own. */
    public static final int TRACKS = 8;

    /** The name of every name past {@link #NAMES}. */
    public static final String OTHER = "(other)";

    /** The rows, as columns: replaced whole when the reach needs another size, never resized in place. */
    private static final class Ring {
        final int mask;
        final long[] seq;
        final long[] mono;
        final long[] frame;
        final long[] after;
        final short[] thread;
        final short[] lane;
        final short[] kind;
        final byte[] track;

        Ring(int n) {
            mask = n - 1;
            seq = new long[n];
            mono = new long[n];
            frame = new long[n];
            after = new long[n];
            thread = new short[n];
            lane = new short[n];
            kind = new short[n];
            track = new byte[n];
        }

        int capacity() {
            return mask + 1;
        }

        /** Row {@code i} of {@code from} into its own slot here. */
        void take(Ring from, long i, long j) {
            int s = (int) (i & from.mask);
            int d = (int) (j & mask);
            seq[d] = from.seq[s];
            mono[d] = from.mono[s];
            frame[d] = from.frame[s];
            after[d] = from.after[s];
            thread[d] = from.thread[s];
            lane[d] = from.lane[s];
            kind[d] = from.kind[s];
            track[d] = from.track[s];
        }
    }

    private volatile Ring ring;

    /** How far back the tape reaches, in nanos; read by everyone, set by {@link #reach(int)} and by a copy. */
    private volatile long reach;

    /** The capacity the reach asks for; the writer makes a ring that size at its next row. */
    private volatile int wanted;

    /** Whose names these are: this tape's own, or, for a frozen copy, the live tape's it was copied from. */
    private final Tape origin;

    // Written by the live tape's writer only; read by anyone, behind the volatile below. A frozen copy shares them.
    private final String[] names;
    private final String[] trackNames;
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

    /** The writer's own: the last frame presented in this run. */
    private long lastPresented = Row.NO_FRAME;

    /** @param seconds how far back the tape reaches, at least one */
    public Tape(int seconds) {
        origin = this;
        names = new String[NAMES];
        trackNames = new String[TRACKS];
        reach(seconds);
        ring = new Ring(wanted);
        name(OTHER);
        presentKind = name(Row.FRAME_PRESENT);
        frameLane = name("frame");
    }

    /** A tape as large as {@code live}, sharing its names, for {@link #copy} to freeze a moment into. */
    private Tape(Tape live) {
        origin = live;
        names = live.names;
        trackNames = live.trackNames;
        reach = live.reach;
        wanted = live.wanted;
        ring = new Ring(live.wanted);
        presentKind = live.presentKind;
        frameLane = live.frameLane;
    }

    /** The ring {@code seconds} need: {@link #RATE} rows a second, a power of two, at most {@link #MOST}. */
    static int capacityFor(int seconds) {
        long rows = Math.min(MOST, (long) seconds * RATE);
        return Integer.highestOneBit((int) Math.max(2, rows - 1)) << 1;
    }

    /**
     * Reach back {@code seconds} from now on. Any thread. Shown at once; the ring that holds it is made by the writer
     * at its next row. A frozen copy keeps the reach it was frozen with.
     */
    public void reach(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("--tape.seconds=" + seconds + ": the tape reaches back at least 1 s");
        }
        wanted = capacityFor(seconds);
        reach = seconds * 1_000_000_000L;
    }

    /**
     * Where this tape's moments are frozen: as large as it, allocated now, so a pause allocates nothing unless the
     * reach has grown since. Only {@link #copy} writes it; never {@link #row}.
     */
    public Tape frozen() {
        return new Tape(this);
    }

    /**
     * Freeze the tape's reach before {@code until} into {@code into}, one of this tape's {@link #frozen} copies: the
     * rows a reader of {@code into} sees from now on, until the next copy, with the reach they were frozen with. Any
     * thread but the writer's, one copy at a time; the writer goes on meanwhile, and rows it overtook during the copy
     * are left out rather than shown torn.
     */
    public void copy(Tape into, long until) {
        if (into.origin != this) {
            throw new IllegalArgumentException("not a frozen copy of this tape");
        }
        Ring r = ring;
        long reachNow = reach;
        long from = at(until - reachNow);
        long to = at(until);
        into.written = 0;
        if (into.ring.capacity() < r.capacity()) {
            into.ring = new Ring(r.capacity());     // the reach grew since the last pause
        }
        Ring d = into.ring;
        int n = 0;
        for (long i = from; i < to; i++, n++) {
            d.take(r, i, n);
        }
        long torn = Math.max(0, first() - from);
        into.reach = reachNow;
        into.runFrom = Math.min(n, torn);
        into.overwritten = overwritten;
        into.written = n;
    }

    // ------------------------------------------------------------------ the writer

    /** A run begins: the rows before it are another run's, and no longer read. */
    public void begin() {
        runFrom = written;
        overwritten = 0;
        lastPresented = Row.NO_FRAME;
    }

    /** The next row, as its header. */
    public void row(Row row) {
        Ring r = ring;
        if (r.capacity() != wanted) {
            r = resize(r);
        }
        long i = written;
        int s = (int) (i & r.mask);
        if (i - r.capacity() >= runFrom && row.monoNanos() - r.mono[s] < reach) {
            overwritten++;
        }
        r.seq[s] = row.seq();
        r.mono[s] = row.monoNanos();
        long f = row.presentedFrame();
        r.frame[s] = f;
        r.after[s] = lastPresented;
        if (f != Row.NO_FRAME && row.is("frame", Row.FRAME_PRESENT)) {
            lastPresented = f;
        }
        r.thread[s] = name(row.thread());
        r.lane[s] = name(row.lane());
        r.kind[s] = name(row.kind());
        r.track[s] = track(row.lane());
        written = i + 1;
    }

    /**
     * A ring the size the reach wants, holding the newest rows of this run the old one held, as many as fit, each at
     * its own index. The writer's, so no row is written while it copies.
     */
    private Ring resize(Ring old) {
        Ring next = new Ring(wanted);
        long w = written;
        long from = Math.max(Math.max(runFrom, w - old.capacity()), w - next.capacity());
        for (long i = from; i < w; i++) {
            next.take(old, i, i);
        }
        if (from > runFrom) {
            runFrom = from;             // what did not fit is no longer held, and is not this run's to read
        }
        ring = next;
        return next;
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

    /** How far back the tape reaches, in nanos: for a frozen copy, the reach it was frozen with. */
    public long reach() {
        return reach;
    }

    /** How many rows it holds at most, now. */
    public int capacity() {
        return ring.capacity();
    }

    /** The index the next row will have; the rows written are those before it. */
    public long written() {
        return written;
    }

    /** The index of the oldest row still held in this run. */
    public long first() {
        long w = written;
        return Math.max(runFrom, w - ring.capacity());
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
        long span = nanos(w - 1) - nanos(f);
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
        Ring r = ring;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (r.mono[(int) (mid & r.mask)] < nanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public long seq(long i) {
        Ring r = ring;
        return r.seq[(int) (i & r.mask)];
    }

    public long nanos(long i) {
        Ring r = ring;
        return r.mono[(int) (i & r.mask)];
    }

    /** The frame the row happened in, or {@link Row#NO_FRAME}. */
    public long frame(long i) {
        Ring r = ring;
        return r.frame[(int) (i & r.mask)];
    }

    /**
     * The last frame presented before the row, or {@link Row#NO_FRAME}: what a row from a file, which has no frame of
     * its own, is known to come after.
     */
    public long after(long i) {
        Ring r = ring;
        return r.after[(int) (i & r.mask)];
    }

    public String thread(long i) {
        Ring r = ring;
        return names[r.thread[(int) (i & r.mask)]];
    }

    public String lane(long i) {
        Ring r = ring;
        return names[r.lane[(int) (i & r.mask)]];
    }

    public String kind(long i) {
        Ring r = ring;
        return names[r.kind[(int) (i & r.mask)]];
    }

    /** The row's track on the strip: its lane's, in the order lanes were first seen. */
    public int track(long i) {
        Ring r = ring;
        return r.track[(int) (i & r.mask)];
    }

    /** Whether the row is a {@code frame.present}: a frame's ruled line. */
    public boolean present(long i) {
        Ring r = ring;
        int s = (int) (i & r.mask);
        return r.kind[s] == presentKind && r.lane[s] == frameLane;
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
        Ring r = ring;
        long start = at(from);
        long end = written;
        long width = Math.max(1, to - from);
        long stop = Math.min(to, until);
        for (long i = start; i < end; i++) {
            int s = (int) (i & r.mask);
            long t = r.mono[s];
            if (t >= stop) {
                break;
            }
            int b = (int) Math.min(bins - 1, Math.max(0, (t - from) * bins / width));
            counts[r.track[s] * bins + b]++;
            if (r.kind[s] == presentKind && r.lane[s] == frameLane) {
                counts[TRACKS * bins + b]++;
            }
        }
        return start >= first() && r == ring;
    }

    /** Tracks named so far. */
    public int tracks() {
        return origin.trackCount;
    }

    /** The row {@code by} rows on from {@code i}, kept within this run's rows; -1 if there are none. */
    public long step(long i, long by) {
        long f = first(), w = written;
        if (w <= f) {
            return -1;
        }
        long j = i + by;
        return j < f ? f : j >= w ? w - 1 : j;
    }

    /** The next frame presented after row {@code i} ({@code dir} 1) or before it (-1); {@code i} if there is none. */
    public long frameStep(long i, int dir) {
        long f = first(), w = written;
        for (long j = i + dir; j >= f && j < w; j += dir) {
            if (present(j)) {
                return j;
            }
        }
        return i;
    }

    /** How far {@link #nearest} looks either way for a row on the track asked for. */
    static final int LOOK = 20_000;

    /**
     * The row nearest {@code nanos} on {@code track}, looking up to {@link #LOOK} rows either way, else the row
     * nearest {@code nanos} on any track; -1 with no rows.
     */
    public long nearest(long nanos, int track) {
        long f = first(), w = written;
        if (w <= f) {
            return -1;
        }
        long j = at(nanos);
        long best = -1;
        long bestGap = Long.MAX_VALUE;
        for (long k = j; k < w && k < j + LOOK; k++) {
            if (track(k) == track) {
                best = k;
                bestGap = nanos(k) - nanos;
                break;
            }
        }
        for (long k = j - 1; k >= f && k > j - LOOK; k--) {
            if (track(k) == track) {
                if (nanos - nanos(k) <= bestGap) {
                    best = k;
                }
                break;
            }
        }
        if (best >= 0) {
            return best;
        }
        if (j >= w) {
            return w - 1;
        }
        return j > f && nanos - nanos(j - 1) <= nanos(j) - nanos ? j - 1 : j;
    }

    /** Track {@code t}'s lane, or {@link #OTHER} for the last when lanes outnumber tracks. */
    public String trackName(int t) {
        return trackNames[t];
    }
}
