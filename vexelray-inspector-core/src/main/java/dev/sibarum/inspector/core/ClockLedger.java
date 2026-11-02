package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Account;
import dev.sibarum.inspector.core.Messages.LedgerAt;
import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ledger per clock (vexelray-inspector's {@code docs/design.md} section 5): for each clock, the turn of it a moment
 * is in, cut by the rows its thread wrote into the four states 0.1's thread ledger named. A pure function of the rows,
 * so the past is recomputed exactly, as every panel's is.
 *
 * <h2>A turn</h2>
 *
 * <p>From one of the clock's own spans' start to the next one's: a pass's {@code frame} zone for the main thread, a
 * draw's {@code present frame} zone for drawing. A zone writes its row when it ends, with how long it took, so it runs
 * back from the row by that long. When the moment's turn has not ended in the rows read, the turn before it is the
 * one accounted, and says so.
 *
 * <h2>The four states</h2>
 *
 * <ul>
 *   <li><b>Waiting</b>: inside a wait on the GPU ({@link #WAITS}), on the clock's thread.</li>
 *   <li><b>Working</b>: inside the clock's own span and not in a wait: a span counts once, as the innermost.</li>
 *   <li><b>Resting</b>: for the main thread, inside {@code wait for events}, the loop's idle zone; for drawing, the
 *       time from a draw's end to the next one's start, when the clock has nothing to draw, by rule rather than by
 *       row, as its note says.</li>
 *   <li><b>Unaccounted</b>: the rest of the turn, between rows. Shown, never shared out among the other three.</li>
 * </ul>
 *
 * <p>Of the working, <b>recording</b> is the {@code emit canvas} zone, the draw's commands recorded on the CPU: fork 8
 * left it on the thread, to be measured before any render-thread talk, and this is that measurement.
 *
 * <p>A <b>wake</b> of the main thread is a {@code wake} row in its turn, which wakes the next pass; its latency is from
 * the first that came while the loop rested to the next pass's start.
 */
public final class ClockLedger {

    /** The clocks the ledger accounts for, in the order the panel shows them. */
    public static final List<String> CLOCKS = List.of(Model.MAIN, Model.DRAWING);

    /** How far either side of the moment its rows are read: a turn longer than this is not found. */
    static final long SPAN = 1_000_000_000L;

    /** What the presenter waits on: the GPU. */
    static final List<String> WAITS = List.of("wait fence", "dxgi wait", "acquire image", "wait image");

    static final String PASS_ZONE = "frame";
    static final String IDLE = "wait for events";
    static final String WAKE = "wake";
    static final String DRAW_ZONE = "present frame";
    static final String RECORDING = "emit canvas";

    private ClockLedger() {
    }

    /** A span a row said: from {@code from} to {@code to}, on the machine's clock. */
    private record Span(long from, long to, Row row) {
    }

    /** The ledger of {@code run} as of {@code atNanos}, from {@code rows}, those stamped near it in any order. */
    static LedgerAt at(String run, List<Row> rows, long atNanos) {
        return new LedgerAt(run, List.of(main(rows, atNanos), drawing(rows, atNanos)));
    }

    /** The main thread's pass {@code atNanos} is in, from its start to the next one's. */
    static Account main(List<Row> rows, long atNanos) {
        List<Span> passes = spans(rows, "frame", PASS_ZONE, null);
        int i = turn(passes, atNanos);
        if (i < 0) {
            return Account.none(Model.MAIN, "no whole pass within a second of the cursor");
        }
        Span pass = passes.get(i);
        long from = pass.from();
        long to = passes.get(i + 1).from();
        String thread = pass.row().thread();
        long waiting = within(spans(rows, "gpu", null, thread), WAITS, from, to);
        long working = Math.max(0L, overlap(pass, from, to) - waiting);
        List<Span> idle = spans(rows, "frame", IDLE, thread);
        long resting = within(idle, null, from, to);
        long recording = within(spans(rows, "draw", RECORDING, thread), null, from, to);
        int wakes = 0;
        long firstWake = Long.MAX_VALUE;
        for (Row r : rows) {
            if (r.lane().equals("frame") && r.kind().equals(WAKE) && r.atNanos() >= from && r.atNanos() < to) {
                wakes++;
                if (r.atNanos() < firstWake && resting(idle, r.atNanos())) {
                    firstWake = r.atNanos();
                }
            }
        }
        return new Account(Model.MAIN, Model.PASS + (pass.row().frame() > 0 ? " #" + pass.row().frame() : ""),
                from, to, working, waiting, resting, unaccounted(from, to, working, waiting, resting), recording,
                wakes, firstWake == Long.MAX_VALUE ? -1L : to - firstWake, note(passes, i, atNanos, "pass"));
    }

    /** The draw {@code atNanos} is in, from its start to the next one's. */
    static Account drawing(List<Row> rows, long atNanos) {
        List<Span> draws = spans(rows, "gpu", DRAW_ZONE, null);
        int i = turn(draws, atNanos);
        if (i < 0) {
            return Account.none(Model.DRAWING, "no whole draw within a second of the cursor");
        }
        Span draw = draws.get(i);
        long from = draw.from();
        long to = draws.get(i + 1).from();
        String thread = draw.row().thread();
        long waiting = within(spans(rows, "gpu", null, thread), WAITS, draw.from(), draw.to());
        long working = Math.max(0L, overlap(draw, from, to) - waiting);
        long resting = Math.max(0L, to - Math.min(to, draw.to()));
        long recording = within(spans(rows, "draw", RECORDING, thread), null, draw.from(), draw.to());
        String number = "";
        int ceiling = 0;
        int gpu = 0;
        for (Row r : rows) {
            if (Model.isDraw(r) && r.thread().equals(thread) && r.atNanos() >= draw.from()
                    && r.atNanos() <= draw.to()) {
                number = Model.numberOf(r);
            } else if (r.lane().equals("frame") && r.atNanos() >= from && r.atNanos() < to) {
                ceiling += r.kind().equals(Model.HELD_FOR_CEILING) ? 1 : 0;
                gpu += r.kind().equals(Model.HELD_FOR_GPU) ? 1 : 0;
            }
        }
        String held = ceiling + gpu == 0 ? "" : "; held " + times(ceiling) + " for the ceiling"
                + (gpu == 0 ? "" : " and " + times(gpu) + " for the GPU");
        String note = note(draws, i, atNanos, "draw");
        return new Account(Model.DRAWING, Model.DRAW + (number.isEmpty() ? "" : " " + number), from, to, working,
                waiting, resting, unaccounted(from, to, working, waiting, resting), recording, -1, -1L,
                (note.isEmpty() ? "resting is between draws" : note + "; resting is between draws") + held);
    }

    private static String times(int n) {
        return n == 1 ? "once" : n + " times";
    }

    /**
     * The zones {@code rows} say on {@code lane}, of {@code kind} (any, when null), on {@code thread} (any, when
     * null), oldest first: a zone's row is its end, and its detail how long it took.
     */
    private static List<Span> spans(List<Row> rows, String lane, String kind, String thread) {
        List<Span> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.lane().equals(lane) || kind != null && !r.kind().equals(kind)
                    || thread != null && !r.thread().equals(thread)) {
                continue;
            }
            long took = Durations.nanos(r.detail());
            if (took >= 0) {
                out.add(new Span(r.atNanos() - took, r.atNanos(), r));
            }
        }
        out.sort(Comparator.comparingLong(Span::from));
        return out;
    }

    /**
     * Which of {@code spans} begins the turn {@code atNanos} is in: the newest to begin by then, or the one before it
     * when it is the newest found, whose turn has not ended. -1 when there is no whole turn.
     */
    private static int turn(List<Span> spans, long atNanos) {
        int i = -1;
        while (i + 1 < spans.size() && spans.get(i + 1).from() <= atNanos) {
            i++;
        }
        if (i == spans.size() - 1) {
            i--;
        }
        return i;
    }

    /** What to say of the turn: that it is the one before the moment's, when that one had not ended. */
    private static String note(List<Span> spans, int i, long atNanos, String what) {
        return i + 1 < spans.size() && spans.get(i + 1).from() <= atNanos
                ? "the " + what + " before the cursor's, which had not ended in the rows read" : "";
    }

    /** How much of {@code from..to} the spans of a kind among {@code kinds} (any, when null) cover. */
    private static long within(List<Span> spans, List<String> kinds, long from, long to) {
        long sum = 0L;
        for (Span s : spans) {
            if (kinds == null || kinds.contains(s.row().kind())) {
                sum += overlap(s, from, to);
            }
        }
        return sum;
    }

    private static long overlap(Span s, long from, long to) {
        return Math.max(0L, Math.min(s.to(), to) - Math.max(s.from(), from));
    }

    private static boolean resting(List<Span> idle, long atNanos) {
        for (Span s : idle) {
            if (atNanos >= s.from() && atNanos <= s.to()) {
                return true;
            }
        }
        return false;
    }

    private static long unaccounted(long from, long to, long working, long waiting, long resting) {
        return Math.max(0L, to - from - working - waiting - resting);
    }
}
