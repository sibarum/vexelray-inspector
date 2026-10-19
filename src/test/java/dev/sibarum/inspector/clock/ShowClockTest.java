package dev.sibarum.inspector.clock;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The show clock against made-up traffic, frame by frame at 144 Hz, with the target's clock running at wall speed:
 * quiet shown at once, a burst stretched within the slack, a storm held near the slack and never past twice it.
 */
class ShowClockTest {

    private static final long MS = 1_000_000L;
    private static final long FRAME = 6_944_444L;      // 144 Hz

    /** Cues as a sorted list; {@link #release} drops the shown ones, as the ring's reader does. */
    private static final class Listed implements ShowClock.Cues {
        final List<Long> times = new ArrayList<>();

        @Override
        public long next(long from) {
            for (long t : times) {
                if (t >= from) {
                    return t;
                }
            }
            return Long.MAX_VALUE;
        }

        @Override
        public int within(long from, long to, int limit) {
            int n = 0;
            for (long t : times) {
                if (t > from && t <= to && n < limit) {
                    n++;
                }
            }
            return n;
        }

        /** Shown up to {@code show}: removed, and how many. */
        int release(long show) {
            int n = 0;
            while (!times.isEmpty() && times.getFirst() <= show) {
                times.removeFirst();
                n++;
            }
            return n;
        }
    }

    /** What playing a script of arrivals frame by frame did. */
    private record Played(long maxLag, long lagAtEnd, int maxShownInAFrame, double minSpeed, double maxSpeed,
                          long burstShownOverNs) {
    }

    /**
     * Play {@code cues} (target times; the target's clock runs at wall speed from 0), each arriving at its own time,
     * for {@code forNs}. The burst is the cues' first to last; how long showing it took is reported.
     */
    private static Played play(long[] cues, long forNs) {
        ShowClock clock = ShowClock.standard();
        Listed pending = new Listed();
        int arrived = 0;
        long maxLag = 0;
        int maxShown = 0;
        double minSpeed = Double.MAX_VALUE, maxSpeed = 0;
        long firstShownAt = -1, lastShownAt = -1;
        int shownTotal = 0;
        long lastLag = 0;
        for (long now = 0; now <= forNs; now += FRAME) {
            while (arrived < cues.length && cues[arrived] <= now) {
                pending.times.add(cues[arrived++]);
            }
            long show = clock.advance(FRAME, now, pending);
            int shown = pending.release(show);
            if (shown > 0) {
                if (firstShownAt < 0) {
                    firstShownAt = now;
                }
                shownTotal += shown;
                if (shownTotal == cues.length) {
                    lastShownAt = now;
                }
            }
            maxShown = Math.max(maxShown, shown);
            lastLag = clock.lag(now);
            maxLag = Math.max(maxLag, lastLag);
            minSpeed = Math.min(minSpeed, clock.speed());
            maxSpeed = Math.max(maxSpeed, clock.speed());
        }
        return new Played(maxLag, lastLag, maxShown, minSpeed, maxSpeed,
                lastShownAt < 0 ? -1 : lastShownAt - firstShownAt);
    }

    private static long[] evenly(long from, int n, long overNs) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = from + overNs * i / n;
        }
        return t;
    }

    @Test
    void quietTrafficIsShownAtRealTime() {
        Played p = play(evenly(100 * MS, 20, 1000 * MS), 1500 * MS);    // 20 a second
        assertEquals(1.0, p.minSpeed(), 1e-9, "never stretched");
        assertTrue(p.maxLag() <= FRAME, "a frame behind at most: " + p.maxLag());
        assertEquals(0, p.lagAtEnd());
    }

    @Test
    void aThousandMessagesInTwentyMillisecondsAreStretchedTenfoldWithinTheSlack() {
        Played p = play(evenly(100 * MS, 1000, 20 * MS), 1000 * MS);
        assertEquals(0.1, p.minSpeed(), 1e-9, "the full stretch");
        assertTrue(p.burstShownOverNs() >= 150 * MS && p.burstShownOverNs() <= 220 * MS,
                "20 ms shown over most of 200: " + p.burstShownOverNs() / MS + " ms");
        assertTrue(p.maxLag() <= 220 * MS, "within the slack, near enough: " + p.maxLag() / MS + " ms");
        assertEquals(0, p.lagAtEnd(), "and caught up once it is over");
    }

    @Test
    void aStormIsHeldWithinTheSlackAndPlaysTogetherToKeepUp() {
        Played p = play(evenly(100 * MS, 12_000, 2000 * MS), 3000 * MS);    // 6,000 a second for two seconds
        assertTrue(p.maxLag() <= 400 * MS, "never past twice the slack: " + p.maxLag() / MS + " ms");
        assertTrue(p.maxShownInAFrame() > 10, "so many came due in one frame: " + p.maxShownInAFrame());
        assertEquals(0, p.lagAtEnd(), "and is current after it");
    }

    @Test
    void aStormSettlesAtRealSpeedInsteadOfFlapping() {
        // After the storm has settled, frame to frame the speed should barely move: no full stretch, no sprint.
        ShowClock clock = ShowClock.standard();
        Listed pending = new Listed();
        long[] cues = evenly(0, 12_000, 2000 * MS);
        int arrived = 0;
        double lo = Double.MAX_VALUE, hi = 0;
        for (long now = 0; now <= 1800 * MS; now += FRAME) {
            while (arrived < cues.length && cues[arrived] <= now) {
                pending.times.add(cues[arrived++]);
            }
            pending.release(clock.advance(FRAME, now, pending));
            if (now > 1000 * MS) {
                lo = Math.min(lo, clock.speed());
                hi = Math.max(hi, clock.speed());
            }
        }
        assertTrue(lo > 0.8 && hi < 1.25, "settled between " + lo + " and " + hi);
    }

    @Test
    void aFrameDrawnLateShowsALaterMomentAndNothingBacksUp() {
        ShowClock clock = ShowClock.standard();
        Listed pending = new Listed();
        pending.times.addAll(List.of(10 * MS, 20 * MS));
        clock.advance(FRAME, 0, pending);
        long show = clock.advance(500 * MS, 500 * MS, pending);    // half a second with no frame
        assertEquals(500 * MS, show, "the next frame is simply current");
    }

    @Test
    void showNeverPassesReal() {
        ShowClock clock = ShowClock.standard();
        Listed pending = new Listed();
        pending.times.add(900 * MS);    // arrived early, as a replay sped up can deliver it
        assertEquals(100 * MS, clock.advance(FRAME, 100 * MS, pending));
    }
}
