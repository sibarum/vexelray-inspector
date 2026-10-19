package dev.sibarum.inspector.clock;

/**
 * The beads in flight: one per message shown, or one per several, each crossing its conveyor in {@link #travelNs}
 * of wall time from the moment show time reached it.
 *
 * <p><b>A bead can carry several messages.</b> A belt only has room for so many beads side by side, so a message
 * that comes due less than {@code minGapNs} after the last bead started on its belt rides on that bead instead, and
 * the bead's {@link #weight} counts it. Every message is on some bead, every bead crosses the whole belt, and a
 * burst shows as a stream of heavier beads rather than a pile at the start. The combo counter has the exact count.
 *
 * <p><b>A fixed pool</b>, allocated once: it is the upper limit on how much moves at once, and so on what a frame
 * costs. With {@code capacity >= lanes * travel / minGap} it never fills; if it does, the bead nearest the end of
 * its crossing finishes early to make room.
 *
 * <p>A bead's place is a function of the wall clock and its start, never stepped frame by frame: a frame drawn late
 * shows every bead further along, and nothing queues behind it.
 *
 * <p>Single-threaded: the timeline's.
 */
public final class BeadPool {

    private final long travelNs;
    private final long minGapNs;
    private final long[] start;
    private final int[] lane;
    private final boolean[] main;
    private final long[] weight;
    private final boolean[] live;
    private final int[] newest;
    private int count;
    private long retiredEarly;

    /**
     * @param capacity how many beads may be in flight at once
     * @param travelNs how long a crossing takes
     * @param lanes    how many belts there are
     * @param minGapNs the least time between two beads starting on one belt
     */
    public BeadPool(int capacity, long travelNs, int lanes, long minGapNs) {
        if (capacity < 1 || travelNs <= 0 || lanes < 1 || minGapNs < 0) {
            throw new IllegalArgumentException("capacity " + capacity + ", travel " + travelNs + ", lanes " + lanes
                    + ", gap " + minGapNs);
        }
        this.travelNs = travelNs;
        this.minGapNs = minGapNs;
        start = new long[capacity];
        lane = new int[capacity];
        main = new boolean[capacity];
        weight = new long[capacity];
        live = new boolean[capacity];
        newest = new int[lanes];
        java.util.Arrays.fill(newest, -1);
    }

    public int capacity() {
        return live.length;
    }

    /** Beads in flight. */
    public int count() {
        return count;
    }

    /** Beads that finished early because the pool was full, since the last {@link #clear}. */
    public long retiredEarly() {
        return retiredEarly;
    }

    /**
     * A message on {@code lane} came due at {@code now}, published on the main thread or not. Returns the slot of
     * the bead carrying it: the belt's newest, if that started less than the gap ago, else a new one.
     */
    public int spawn(int lane, boolean main, long now) {
        int last = newest[lane];
        if (last >= 0 && live[last] && this.lane[last] == lane && now - start[last] < minGapNs) {
            weight[last]++;
            return last;
        }
        int slot = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < live.length; i++) {
            if (!live[i]) {
                slot = i;
                break;
            }
            if (start[i] < oldest) {
                oldest = start[i];
                slot = i;
            }
        }
        if (live[slot]) {
            retiredEarly++;
        } else {
            count++;
        }
        live[slot] = true;
        start[slot] = now;
        weight[slot] = 1;
        this.lane[slot] = lane;
        this.main[slot] = main;
        newest[lane] = slot;
        return slot;
    }

    /** Whether {@code slot} is in flight. */
    public boolean live(int slot) {
        return live[slot];
    }

    /** How far across {@code slot} is at {@code now}, 0 to 1; a bead that has arrived is retired, and gives 1. */
    public double progress(int slot, long now) {
        double p = (double) (now - start[slot]) / travelNs;
        if (p >= 1 && live[slot]) {
            live[slot] = false;
            count--;
        }
        return Math.max(0, Math.min(1, p));
    }

    public int lane(int slot) {
        return lane[slot];
    }

    public boolean main(int slot) {
        return main[slot];
    }

    /** How many messages {@code slot} carries. */
    public long weight(int slot) {
        return weight[slot];
    }

    /** No beads. */
    public void clear() {
        java.util.Arrays.fill(live, false);
        java.util.Arrays.fill(newest, -1);
        count = 0;
        retiredEarly = 0;
    }
}
