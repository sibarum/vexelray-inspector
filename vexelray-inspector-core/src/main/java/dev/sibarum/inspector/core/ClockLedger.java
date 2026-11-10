package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Account;
import dev.sibarum.inspector.core.Messages.LedgerAt;
import dev.sibarum.inspector.core.Messages.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The ledger per clock (vexelray-inspector's {@code docs/design.md} section 5): for each clock, the turn of it a moment
 * is in, cut by the rows its thread wrote into the four states 0.1's thread ledger named; or, over a stretch selected,
 * every whole turn inside it, summed. A pure function of the rows, so the past is recomputed exactly, as every panel's
 * is.
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
 * thread's. A job's turn is its {@code job <name>} zone and the time to the next job, rest by rule, as drawing's is;
 * a run whose jobs write no rows (FN-15) says so rather than show a turn of nothing.
 *
 * <p>Of the working, <b>recording</b> is the {@code emit canvas} zone, the draw's commands recorded on the CPU: fork 8
 * left it on the thread, to be measured before any render-thread talk, and this is that measurement.
 *
 * <p>A <b>wake</b> of the main thread is a {@code wake} row in its turn, which wakes the next pass; its latency is from
 * the first that came while the loop rested to the next pass's start.
 *
 * <h2>Over a stretch</h2>
 *
 * <p>Each clock's turns that begin and end inside it, each accounted as above and added up: the turn says how many,
 * each state is their sum, the wakes too, and the wake's latency is the longest of theirs. What every turn's note says
 * is kept; what only some say (a draw's holds) is left to the readouts. The rows are read once and each clock's spans
 * found once, kept in time order, so a minute's thousands of turns cost a search each rather than a scan.
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

    /**
     * How far past a stretch's ends its rows are read: a zone's row is written when it ends, so the turn after the
     * stretch's last is found by a row a little after it.
     */
    static final long SLACK = 250_000_000L;

    /** How far back from the newest row a following ledger first reads; four times as far while a turn is not whole. */
    static final long FIRST_LOOK = 50_000_000L;

    /** What the presenter waits on: the GPU. */
    static final List<String> WAITS = List.of("wait fence", "dxgi wait", "acquire image", "wait image");

    /** What the note of a stretch's wakes says, when there were any while the clock rested. */
    static final String LONGEST_WAKE = "wake to run: the longest";

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

    /** The account of a clock's {@code i}th turn, saying {@code note} of it. */
    private interface Turn {
        Account of(int i, String note);
    }

    /**
     * A clock's turns, read from the rows once: the spans that begin them, oldest first, and the account of any one.
     *
     * @param what     a turn, in words ({@code pass}), for the notes and the count of a stretch's
     * @param noneAt   what is said when no whole turn holds a moment
     * @param noneOver what is said when no whole turn is inside a stretch
     */
    private record Clock(String name, String what, List<Span> turns, Turn account, String noneAt, String noneOver) {

        /** The account of the turn {@code atNanos} is in, or none. */
        Account at(long atNanos) {
            int i = turn(turns, atNanos);
            return i < 0 ? Account.none(name, noneAt) : account.of(i, note(turns, i, atNanos, what));
        }

        /** Every whole turn that begins at or after {@code fromNanos} and ends by {@code toNanos}, summed; or none. */
        Account over(long fromNanos, long toNanos) {
            List<Account> each = new ArrayList<>();
            for (int i = 0; i + 1 < turns.size(); i++) {
                if (turns.get(i).from() >= fromNanos && turns.get(i + 1).from() <= toNanos) {
                    each.add(account.of(i, ""));
                }
            }
            return each.isEmpty() ? Account.none(name, noneOver) : summed(name, what, each);
        }
    }

    /**
     * The ledger of {@code run} as of {@code atNanos}, from {@code rows}, those stamped near it in any order; its
     * logic ticks are those on {@code logicThread}, empty when no row has shown one off the main thread.
     */
    static LedgerAt at(String run, List<Row> rows, long atNanos, String logicThread) {
        return new LedgerAt(run, List.of(mainClock(rows).at(atNanos), logicClock(rows, logicThread).at(atNanos),
                drawClock(rows).at(atNanos), jobsClock(rows).at(atNanos)));
    }

    /**
     * The ledger of {@code run} over the stretch from {@code fromNanos} to {@code toNanos}: each clock's whole turns
     * inside it, summed, from {@code rows}, those stamped in it and {@link #SLACK} either side, in any order.
     */
    static LedgerAt over(String run, List<Row> rows, long fromNanos, long toNanos, String logicThread) {
        return new LedgerAt(run, List.of(mainClock(rows).over(fromNanos, toNanos),
                logicClock(rows, logicThread).over(fromNanos, toNanos), drawClock(rows).over(fromNanos, toNanos),
                jobsClock(rows).over(fromNanos, toNanos)));
    }

    /**
     * {@code each}, the accounts of a clock's turns in order, as one: how many there were, each state summed (a figure
     * a turn cannot fill leaves the sum unfilled too), the wakes summed, the longest latency, and what every note says.
     */
    static Account summed(String clock, String what, List<Account> each) {
        long working = 0L;
        long waiting = 0L;
        long resting = 0L;
        long unaccounted = 0L;
        long recording = 0L;
        int wakes = 0;
        long latency = -1L;
        for (Account a : each) {
            working = plus(working, a.working());
            waiting = plus(waiting, a.waiting());
            resting = plus(resting, a.resting());
            unaccounted = plus(unaccounted, a.unaccounted());
            recording = plus(recording, a.recording());
            wakes = wakes < 0 || a.wakes() < 0 ? -1 : wakes + a.wakes();
            latency = Math.max(latency, a.latency());
        }
        List<String> common = new ArrayList<>(List.of(each.getFirst().note().split("; ")));
        for (Account a : each) {
            List<String> parts = List.of(a.note().split("; "));
            common.removeIf(p -> !parts.contains(p));
        }
        common.removeIf(String::isEmpty);
        if (latency >= 0) {
            common.add(LONGEST_WAKE);
        }
        int n = each.size();
        String turns = n + " " + (n == 1 ? what : what.endsWith("s") ? what + "es" : what + "s");
        return new Account(clock, turns, each.getFirst().fromNanos(), each.getLast().toNanos(), working, waiting,
                resting, unaccounted, recording, wakes, latency, join(common.toArray(String[]::new)));
    }

    private static long plus(long sum, long n) {
        return sum < 0 || n < 0 ? -1L : sum + n;
    }

    /**
     * The job lane's runs, each from a job's start to the next one's on any of the lane's threads: the job's zone is
     * its work, and the time to the next job is rest, by rule, as drawing's between draws is: the lane's threads wait
     * for a request in the executor, which writes no row. Jobs may overlap, and then there is none.
     */
    static Account jobs(List<Row> rows, long atNanos) {
        return jobsClock(rows).at(atNanos);
    }

    private static Clock jobsClock(List<Row> rows) {
        List<Span> jobs = new ArrayList<>();
        for (Span s : spans(rows, "bus", null, null)) {
            if (s.row().kind().startsWith(Model.JOB_ZONE)) {
                jobs.add(s);
            }
        }
        String unwritten = "none asked, or a run whose jobs write no rows (FN-15)";
        return new Clock(JOBS, "job", jobs, (i, note) -> {
            Span job = jobs.get(i);
            long from = job.from();
            long to = jobs.get(i + 1).from();
            long working = overlap(job, from, to);
            long resting = Math.max(0L, to - Math.min(to, job.to()));
            String name = job.row().kind().substring(Model.JOB_ZONE.length());
            return new Account(JOBS, Model.JOB + " " + name, from, to, working, -1L, resting,
                    unaccounted(from, to, working, 0L, resting), -1L, -1, -1L,
                    join(note, "on " + job.row().thread(), "resting is between jobs"));
        }, "no whole job within a second of the cursor: " + unwritten, "no whole job inside the stretch: " + unwritten);
    }

    /**
     * The logic lane's tick {@code atNanos} is in, from its start to the next one's: the tick's zone is its work, the
     * lane's park its rest ({@link #PARK}), and a {@code wake} naming the lane ({@link #LOGIC_WOKEN}) what ended the park.
     * A run whose park writes no row (FN-17, before the framework wrote one) leaves the rest unaccounted. Under
     * {@code --logic=main} the ticks are the main thread's named the logic's, and the rest of the turn is the main
     * thread's, so it is unaccounted here.
     */
    static Account logic(List<Row> rows, long atNanos, String logicThread) {
        return logicClock(rows, logicThread).at(atNanos);
    }

    private static Clock logicClock(List<Row> rows, String logicThread) {
        if (logicThread.isEmpty()) {
            String none = "no logic tick in the reach: idle, or --logic=main in a run whose ticks name nobody (FN-16)";
            return new Clock(Model.LOGIC, "tick", List.of(), null, none, none);
        }
        boolean onMain = logicThread.equals("main");
        List<Span> ticks = logicTicks(rows, logicThread);
        List<Row> ran = inOrder(rows, r -> r.kind().equals(Model.TICK_RAN) && r.thread().equals(logicThread)
                && Model.isLogicTick(r));
        List<Span> parks = spans(rows, "bus", PARK, logicThread);
        List<Row> wakes = inOrder(rows, r -> r.lane().equals("bus") && r.kind().equals(WAKE)
                && r.detail().equals(LOGIC_WOKEN));
        return new Clock(Model.LOGIC, "tick", ticks, (i, note) -> {
            Span tick = ticks.get(i);
            long from = tick.from();
            long to = ticks.get(i + 1).from();
            List<Row> after = between(ran, tick.to(), to);
            String number = after.isEmpty() ? "" : Model.numberOf(after.getFirst());
            long working = overlap(tick, from, to);
            String over = tick.to() - tick.from() > TICK_DEADLINE ? "over its 8 ms deadline" : "";
            String turn = Model.TICK + (number.isEmpty() ? "" : " " + number);
            if (onMain) {
                return new Account(Model.LOGIC, turn, from, to, working, -1L, -1L,
                        unaccounted(from, to, working, 0L, 0L), -1L, -1, -1L,
                        join(note, "on the main thread: the rest of its turn is the main thread's", over));
            }
            if (parks.isEmpty()) {
                return new Account(Model.LOGIC, turn, from, to, working, -1L, -1L,
                        unaccounted(from, to, working, 0L, 0L), -1L, -1, -1L,
                        join(note, "its park writes no row (FN-17)", over));
            }
            long resting = within(parks, null, null, from, to);
            List<Row> woke = between(wakes, from, to);
            long firstWake = Long.MAX_VALUE;
            for (Row r : woke) {
                if (r.atNanos() < firstWake && resting(parks, null, r.atNanos())) {
                    firstWake = r.atNanos();
                }
            }
            return new Account(Model.LOGIC, turn, from, to, working, -1L, resting,
                    unaccounted(from, to, working, 0L, resting), -1L, woke.size(),
                    firstWake == Long.MAX_VALUE ? -1L : to - firstWake, join(note, over));
        }, "no whole tick within a second of the cursor: idle", "no whole tick inside the stretch: idle");
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
        return mainClock(rows).at(atNanos);
    }

    private static Clock mainClock(List<Row> rows) {
        List<Span> passes = spans(rows, "frame", PASS_ZONE, null);
        List<Span> gpu = spans(rows, "gpu", null, null);
        List<Span> idle = spans(rows, "frame", IDLE, null);
        List<Span> recordings = spans(rows, "draw", RECORDING, null);
        List<Row> wakes = inOrder(rows, r -> r.lane().equals("frame") && r.kind().equals(WAKE));
        return new Clock(Model.MAIN, "pass", passes, (i, note) -> {
            Span pass = passes.get(i);
            long from = pass.from();
            long to = passes.get(i + 1).from();
            String thread = pass.row().thread();
            long waiting = within(gpu, WAITS, thread, from, to);
            long working = Math.max(0L, overlap(pass, from, to) - waiting);
            long resting = within(idle, null, thread, from, to);
            long recording = within(recordings, null, thread, from, to);
            List<Row> woke = between(wakes, from, to);
            long firstWake = Long.MAX_VALUE;
            for (Row r : woke) {
                if (r.atNanos() < firstWake && resting(idle, thread, r.atNanos())) {
                    firstWake = r.atNanos();
                }
            }
            return new Account(Model.MAIN, Model.PASS + (pass.row().frame() > 0 ? " #" + pass.row().frame() : ""),
                    from, to, working, waiting, resting, unaccounted(from, to, working, waiting, resting), recording,
                    woke.size(), firstWake == Long.MAX_VALUE ? -1L : to - firstWake, note);
        }, "no whole pass within a second of the cursor", "no whole pass inside the stretch");
    }

    /** The draw {@code atNanos} is in, from its start to the next one's. */
    static Account drawing(List<Row> rows, long atNanos) {
        return drawClock(rows).at(atNanos);
    }

    private static Clock drawClock(List<Row> rows) {
        List<Span> draws = spans(rows, "gpu", DRAW_ZONE, null);
        List<Span> gpu = spans(rows, "gpu", null, null);
        List<Span> recordings = spans(rows, "draw", RECORDING, null);
        List<Row> ran = inOrder(rows, Model::isDraw);
        List<Row> holds = inOrder(rows, r -> r.lane().equals("frame") && !Model.isDraw(r)
                && (r.kind().equals(Model.HELD_FOR_CEILING) || r.kind().equals(Model.HELD_FOR_GPU)));
        return new Clock(Model.DRAWING, "draw", draws, (i, note) -> {
            Span draw = draws.get(i);
            long from = draw.from();
            long to = draws.get(i + 1).from();
            String thread = draw.row().thread();
            long waiting = within(gpu, WAITS, thread, draw.from(), draw.to());
            long working = Math.max(0L, overlap(draw, from, to) - waiting);
            long resting = Math.max(0L, to - Math.min(to, draw.to()));
            long recording = within(recordings, null, thread, draw.from(), draw.to());
            String number = "";
            for (Row r : between(ran, draw.from(), draw.to() + 1)) {
                if (r.thread().equals(thread)) {
                    number = Model.numberOf(r);
                }
            }
            int ceiling = 0;
            int held = 0;
            for (Row r : between(holds, from, to)) {
                ceiling += r.kind().equals(Model.HELD_FOR_CEILING) ? 1 : 0;
                held += r.kind().equals(Model.HELD_FOR_GPU) ? 1 : 0;
            }
            String holding = ceiling + held == 0 ? "" : "held " + times(ceiling) + " for the ceiling"
                    + (held == 0 ? "" : " and " + times(held) + " for the GPU");
            return new Account(Model.DRAWING, Model.DRAW + (number.isEmpty() ? "" : " " + number), from, to, working,
                    waiting, resting, unaccounted(from, to, working, waiting, resting), recording, -1, -1L,
                    join(note, "resting is between draws", holding));
        }, "no whole draw within a second of the cursor", "no whole draw inside the stretch");
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

    /** The rows {@code which} takes, in time order, for {@link #between} to search. */
    private static List<Row> inOrder(List<Row> rows, Predicate<Row> which) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (which.test(r)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(Row::atNanos));
        return out;
    }

    /** Those of {@code sorted}, in time order, stamped at or after {@code from} and before {@code to}. */
    private static List<Row> between(List<Row> sorted, long from, long to) {
        int lo = firstAtOrAfter(sorted, from);
        int hi = firstAtOrAfter(sorted, to);
        return sorted.subList(lo, Math.max(lo, hi));
    }

    private static int firstAtOrAfter(List<Row> sorted, long at) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).atNanos() < at) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Where in {@code spans}, oldest first, to begin looking for those reaching {@code atNanos}: a span beginning more
     * than {@link #SPAN} before it, longer than any turn the ledger finds, is not looked at.
     */
    private static int firstFrom(List<Span> spans, long atNanos) {
        long at = atNanos - SPAN;
        int lo = 0;
        int hi = spans.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).from() < at) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
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

    /**
     * How much of {@code from..to} the spans of {@code spans}, oldest first, of a kind among {@code kinds} (any, when
     * null) and on {@code thread} (any, when null) cover.
     */
    private static long within(List<Span> spans, List<String> kinds, String thread, long from, long to) {
        long sum = 0L;
        for (int i = firstFrom(spans, from); i < spans.size() && spans.get(i).from() < to; i++) {
            Span s = spans.get(i);
            if ((kinds == null || kinds.contains(s.row().kind()))
                    && (thread == null || s.row().thread().equals(thread))) {
                sum += overlap(s, from, to);
            }
        }
        return sum;
    }

    private static long overlap(Span s, long from, long to) {
        return Math.max(0L, Math.min(s.to(), to) - Math.max(s.from(), from));
    }

    /** Whether {@code atNanos} is inside one of {@code idle}, oldest first, on {@code thread} (any, when null). */
    private static boolean resting(List<Span> idle, String thread, long atNanos) {
        for (int i = firstFrom(idle, atNanos); i < idle.size() && idle.get(i).from() <= atNanos; i++) {
            Span s = idle.get(i);
            if (atNanos <= s.to() && (thread == null || s.row().thread().equals(thread))) {
                return true;
            }
        }
        return false;
    }

    private static long unaccounted(long from, long to, long working, long waiting, long resting) {
        return Math.max(0L, to - from - working - waiting - resting);
    }
}
