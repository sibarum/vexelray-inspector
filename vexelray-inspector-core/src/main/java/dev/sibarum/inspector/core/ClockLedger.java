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
 * draw's {@code present frame} zone for drawing, a {@code tick} zone on the logic lane's own thread for the logic. A
 * zone writes its row when it ends, with how long it took, so it runs
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
 * <p>The logic lane's thread parks between ticks in an idle zone, {@code logic park}, and what ends the park writes a
 * {@code wake}, so its rest and its wakes are read as the main thread's are; a run built before the framework wrote
 * them leaves the rest unaccounted and says so (framework-notes FN-17). Under {@code --logic=main} its ticks are the
 * main thread's that their {@code tick.ran} row names the logic's (FN-16), and the rest of each turn is the main
 * thread's. A job writes no rows at all (FN-15), and says so rather than show a turn of nothing.
 *
 * <p>Of the working, <b>recording</b> is the {@code emit canvas} zone, the draw's commands recorded on the CPU: fork 8
 * left it on the thread, to be measured before any render-thread talk, and this is that measurement.
 *
 * <p>A <b>wake</b> of the main thread is a {@code wake} row in its turn, which wakes the next pass; its latency is from
 * the first that came while the loop rested to the next pass's start.
 */
public final class ClockLedger {

    /** The clocks the ledger accounts for, in the order the panel shows them. */
    public static final List<String> CLOCKS = List.of(Model.MAIN, Model.LOGIC, Model.DRAWING, ClockLedger.JOBS);

    /** The job lane's clock, named as its lane on the timeline is. */
    public static final String JOBS = Model.JOBS;

    /** A tick's deadline: still atchung's 8 ms placeholder (ROADMAP phase 4). */
    static final long TICK_DEADLINE = 8_000_000L;

    /** How far either side of the moment its rows are read: a turn longer than this is not found. */
    static final long SPAN = 1_000_000_000L;

    /** How far back from the newest row a following ledger first reads; four times as far while a turn is not whole. */
    static final long FIRST_LOOK = 50_000_000L;

    /** What the presenter waits on: the GPU. */
    static final List<String> WAITS = List.of("wait fence", "dxgi wait", "acquire image", "wait image");

    static final String PASS_ZONE = "frame";
    static final String IDLE = "wait for events";
    static final String WAKE = "wake";
    static final String DRAW_ZONE = "present frame";
    static final String RECORDING = "emit canvas";
    static final String TICK_ZONE = "tick";
    /** The logic lane's park between ticks, an idle zone on the bus lane (vexelray-framework's {@code Logic.PARK}). */
    static final String PARK = "logic park";
    /** What the {@code wake} that ends the logic lane's park says ({@code Logic.WOKEN}). */
    static final String LOGIC_WOKEN = "message on logic";

    private ClockLedger() {
    }

    /**
     * Whether the ledger reads {@code r} at all: its clocks' zones, their waits and rests, the wakes, the draws' own
     * rows and the holds. The rest, most of a busy run's (the bus's sends and deliveries), is left on the tape.
     */
    static boolean reads(Row r) {
        String k = r.kind();
        return switch (r.lane()) {
            case "frame" -> k.equals(PASS_ZONE) || k.equals(IDLE) || k.equals(WAKE) || k.equals(Model.HELD_FOR_CEILING)
                    || k.equals(Model.HELD_FOR_GPU);
            case "gpu" -> k.equals(DRAW_ZONE) || WAITS.contains(k);
            case "draw" -> k.equals(RECORDING) || k.equals(Model.DRAW_RAN);
            case "bus" -> k.equals(TICK_ZONE) || k.equals(Model.TICK_RAN) || k.equals(PARK) || k.startsWith(Model.JOB_ZONE)
                    || k.equals(WAKE) && r.detail().equals(LOGIC_WOKEN);
            default -> false;
        };
    }

    /** A span a row said: from {@code from} to {@code to}, on the machine's clock. */
    private record Span(long from, long to, Row row) {
    }

    /**
     * The ledger of {@code run} as of {@code atNanos}, from {@code rows}, those stamped near it in any order; its
     * logic ticks are those on {@code logicThread}, empty when no row has shown one off the main thread.
     */
    static LedgerAt at(String run, List<Row> rows, long atNanos, String logicThread) {
        return new LedgerAt(run, List.of(main(rows, atNanos), logic(rows, atNanos, logicThread),
                drawing(rows, atNanos), jobs(rows, atNanos)));
    }

    /**
     * The job lane's run {@code atNanos} is in, from a job's start to the next one's on any of the lane's threads: the
     * job's zone is its work, and the time to the next job is rest, by rule, as drawing's between draws is: the lane's
     * threads wait for a request in the executor, which writes no row. Jobs may overlap, and then there is none.
     */
    static Account jobs(List<Row> rows, long atNanos) {
        List<Span> jobs = new ArrayList<>();
        for (Span s : spans(rows, "bus", null, null)) {
            if (s.row().kind().startsWith(Model.JOB_ZONE)) {
                jobs.add(s);
            }
        }
        int i = turn(jobs, atNanos);
        if (i < 0) {
            return Account.none(JOBS, "no whole job within a second of the cursor: none asked, or a run whose jobs"
                    + " write no rows (FN-15)");
        }
        Span job = jobs.get(i);
        long from = job.from();
        long to = jobs.get(i + 1).from();
        long working = overlap(job, from, to);
        long resting = Math.max(0L, to - Math.min(to, job.to()));
        String name = job.row().kind().substring(Model.JOB_ZONE.length());
        return new Account(JOBS, Model.JOB + " " + name, from, to, working, -1L, resting,
                unaccounted(from, to, working, 0L, resting), -1L, -1, -1L,
                join(note(jobs, i, atNanos, "job"), "on " + job.row().thread() + "; resting is between jobs"));
    }

    /**
     * The logic lane's tick {@code atNanos} is in, from its start to the next one's: the tick's zone is its work, the
     * lane's park its rest ({@link #PARK}), and a {@code wake} naming the lane ({@link #LOGIC_WOKEN}) what ended the park.
     * A run whose park writes no row (FN-17, before the framework wrote one) leaves the rest unaccounted. Under
     * {@code --logic=main} the ticks are the main thread's named the logic's, and the rest of the turn is the main
     * thread's, so it is unaccounted here.
     */
    static Account logic(List<Row> rows, long atNanos, String logicThread) {
        if (logicThread.isEmpty()) {
            return Account.none(Model.LOGIC, "no logic tick in the reach: idle, or --logic=main in a run whose ticks"
                    + " name nobody (FN-16)");
        }
        boolean onMain = logicThread.equals("main");
        List<Span> ticks = logicTicks(rows, logicThread);
        int i = turn(ticks, atNanos);
        if (i < 0) {
            return Account.none(Model.LOGIC, "no whole tick within a second of the cursor: idle");
        }
        Span tick = ticks.get(i);
        long from = tick.from();
        long to = ticks.get(i + 1).from();
        String number = "";
        for (Row r : rows) {
            if (r.kind().equals(Model.TICK_RAN) && r.thread().equals(logicThread) && Model.isLogicTick(r)
                    && r.atNanos() >= tick.to() && r.atNanos() < to) {
                number = Model.numberOf(r);
                break;
            }
        }
        long working = overlap(tick, from, to);
        String note = note(ticks, i, atNanos, "tick");
        String over = tick.to() - tick.from() > TICK_DEADLINE ? "over its 8 ms deadline" : "";
        String turn = Model.TICK + (number.isEmpty() ? "" : " " + number);
        if (onMain) {
            return new Account(Model.LOGIC, turn, from, to, working, -1L, -1L, unaccounted(from, to, working, 0L, 0L),
                    -1L, -1, -1L, join(note, "on the main thread: the rest of its turn is the main thread's", over));
        }
        List<Span> parks = spans(rows, "bus", PARK, logicThread);
        if (parks.isEmpty()) {
            return new Account(Model.LOGIC, turn, from, to, working, -1L, -1L, unaccounted(from, to, working, 0L, 0L),
                    -1L, -1, -1L, join(note, "its park writes no row (FN-17)", over));
        }
        long resting = within(parks, null, from, to);
        int wakes = 0;
        long firstWake = Long.MAX_VALUE;
        for (Row r : rows) {
            if (r.lane().equals("bus") && r.kind().equals(WAKE) && r.detail().equals(LOGIC_WOKEN)
                    && r.atNanos() >= from && r.atNanos() < to) {
                wakes++;
                if (r.atNanos() < firstWake && resting(parks, r.atNanos())) {
                    firstWake = r.atNanos();
                }
            }
        }
        return new Account(Model.LOGIC, turn, from, to, working, -1L, resting,
                unaccounted(from, to, working, 0L, resting), -1L, wakes,
                firstWake == Long.MAX_VALUE ? -1L : to - firstWake, join(note, over));
    }

    /**
     * The logic's ticks on {@code thread}, oldest first: every tick zone there off the main thread; on it, only those
     * whose {@code tick.ran} row, the next one after on that thread, names the tick the logic's (FN-16).
     */
    private static List<Span> logicTicks(List<Row> rows, String thread) {
        List<Span> ticks = spans(rows, "bus", TICK_ZONE, thread);
        if (!thread.equals("main")) {
            return ticks;
        }
        List<Row> ran = new ArrayList<>();
        for (Row r : rows) {
            if (r.lane().equals("bus") && r.kind().equals(Model.TICK_RAN) && r.thread().equals(thread)) {
                ran.add(r);
            }
        }
        ran.sort(Comparator.comparingLong(Row::seq));
        List<Span> logic = new ArrayList<>();
        for (Span s : ticks) {
            for (Row r : ran) {
                if (r.seq() > s.row().seq()) {
                    if (Model.isLogicTick(r)) {
                        logic.add(s);
                    }
                    break;
                }
            }
        }
        return logic;
    }

    /** {@code parts} that say anything, a semicolon apart. */
    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                b.append(b.isEmpty() ? "" : "; ").append(p);
            }
        }
        return b.toString();
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
                ? "the " + what + " before: the cursor's had not ended" : "";
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
