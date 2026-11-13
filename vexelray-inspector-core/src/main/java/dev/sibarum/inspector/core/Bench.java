package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.ChildExited;
import dev.sibarum.inspector.core.Messages.Choose;
import dev.sibarum.inspector.core.Messages.ClearRange;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Detach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Details;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.ExportFrame;
import dev.sibarum.inspector.core.Messages.Exported;
import dev.sibarum.inspector.core.Messages.WriteExport;
import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Follow;
import dev.sibarum.inspector.core.Messages.GoTo;
import dev.sibarum.inspector.core.Messages.GoToRow;
import dev.sibarum.inspector.core.Messages.GoToTick;
import dev.sibarum.inspector.core.Messages.LaunchChild;
import dev.sibarum.inspector.core.Messages.Leave;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Paused;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Replay;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.LedgerAt;
import dev.sibarum.inspector.core.Messages.RowsAround;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.RunPanel;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.sibarum.inspector.core.Messages.Range;
import dev.sibarum.inspector.core.Messages.Seek;
import dev.sibarum.inspector.core.Messages.SelectRange;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.StepEvent;
import dev.sibarum.inspector.core.Messages.StepRow;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WindowOpened;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The run being inspected, and everything known of it: which run, what has been read of it, and the model the panels
 * show (vexelray-inspector's {@code docs/design.md} section 8). One immutable {@link State} on a ledger; each message
 * runs one declared step, and each step that changes what is shown publishes the {@link Timeline} the view draws.
 *
 * <p><b>Attaching.</b> The first window asks which runs there are, and the timer asks again every
 * {@link #LOOK_AGAIN}; each answer is the run panel's choices. While none is attached, the newest is, unless a run was
 * chosen: picked in the run panel ({@link Choose}), named by {@code --attach} (attached once it is running), or
 * detached from ({@link Leave}). A run that was found and goes away is replaced by the next one the timer finds; a
 * chosen one stays on screen, its tape to step through, until another is chosen.
 *
 * <p><b>The model, live and as of the cursor.</b> Each batch is folded onto the live {@link Model} and kept on the
 * {@link Tape}, a second at a time beside a checkpoint, for the last {@link #RETAIN}; the model as of any moment is
 * recomputed from the tape by the same fold ({@link Tape#asOf}), exactly as it was. The passes lane needs none of
 * that: it shows every event kept, which is the same fold over the retained reach, and puts the cursor at its right
 * edge, so at rest it shows the events as of the cursor and while it glides it shows what it passes (design.md 9.8).
 * The {@link Readouts} are where the model as of the cursor is shown, sent beside every timeline. So are the
 * {@link Details}: what the run's clock said at the cursor, and the event it is on.
 *
 * <p><b>The island.</b> The live tape lets go of what is older than {@link #RETAIN} as rows arrive, whether or not the
 * cursor is stopped there, so a stopped cursor would lose what it shows. Stopping it freezes the tape and the kept
 * events as they stand into an {@link Island}, which nothing trims, and the cursor cannot leave it: every move is
 * clamped to it, and its newest end says how to get back. Recording goes on beside it, on the live tape, so following
 * again ({@link Follow}, or {@link Pause} while stopped) finds the present as it is and lets the island go. A late row
 * stamped inside the island is still added to it, as it was before there were islands.
 *
 * <p><b>The cursor.</b> {@link Pause} stops it where the view's lane comes to rest, or follows again; {@link Follow}
 * only follows; {@link Step} moves it by time, from that resting moment if following, {@link GoTo} onto a clicked
 * event, {@link Seek} to a clicked moment with no event near, and {@link StepEvent} onto the next or previous event of
 * the lane the view has focused, which it is then on and the status line describes. Rows go on arriving and being kept
 * while paused, and are shown only if they are before the cursor (a late batch), so a paused timeline is still. A run
 * that goes away keeps its tape, to step through, until another is attached.
 */
@Component
@Hears({WindowOpened.class, Runs.class, Rows.class, Detached.class, ChildExited.class, Choose.class, Leave.class,
        Pause.class, Follow.class, Step.class, StepEvent.class, GoTo.class, Seek.class, StepRow.class, GoToRow.class,
        GoToTick.class, SelectRange.class, ClearRange.class, ExportFrame.class, Picked.class, Exported.class})
@Publishes({FindRuns.class, Attach.class, Detach.class, Replay.class, LaunchChild.class, Timeline.class, Readouts.class,
        Details.class, RunPanel.class, Flows.class, Traced.class, RowsAround.class, LedgerAt.class, PickFile.class,
        WriteExport.class, Paused.class})
public final class Bench {

    /** How often the timer asks which runs there are. */
    static final Duration LOOK_AGAIN = Duration.ofSeconds(2);

    /** How far back a model's passes reach: the lane's width in time. */
    static final long REACH = 10_000_000_000L;

    /** How far back the tape keeps rows: as far as the cursor can go. How long memory allows is R1's to measure. */
    static final long RETAIN = 60_000_000_000L;

    /** A reach that lets nothing go: what an island keeps. */
    static final long FOREVER = Long.MAX_VALUE;

    /** How many of each message the bench's inbox holds: rows arrive a batch per 10 ms. */
    static final int CAPACITY = 1024;

    /** How many rows the rows panel shows: as many as fit beside the details. */
    public static final int AROUND = 11;

    /** What the status line says while nothing can be inspected. */
    static final String NONE = "No application to inspect. Start one with --automation=0";

    /** The moment {@code reachNanos} before {@code newestNanos}, or the earliest there is for {@link #FOREVER}. */
    static long since(long newestNanos, long reachNanos) {
        return reachNanos == FOREVER ? Long.MIN_VALUE : newestNanos - reachNanos;
    }

    /**
     * What a stopped cursor is kept on: the tape and the kept events as they stood when it stopped, with the late rows
     * stamped inside it added since. Nothing is let go of it, and the cursor stays between its ends.
     *
     * @param tape     the rows, with their checkpoints
     * @param kept     every event the lanes show
     * @param endNanos its newest end: the newest row when it was frozen, or where the cursor stopped if that was later
     * @param selection the stretch of it selected ({@link SelectRange}), which the readouts and flows count over; let go
     *                  with it
     */
    public record Island(Tape tape, Model kept, long endNanos, Optional<Selection> selection) {

        /** None: following. */
        static final Island NONE = new Island(Tape.EMPTY, Model.EMPTY, 0L, Optional.empty());

        /** {@code s}'s live tape and kept events frozen, its end at the newest row or {@code atNanos}, the later. */
        static Island of(State s, long atNanos) {
            return new Island(s.tape(), s.kept(), Math.max(s.now().newestNanos(), atNanos), Optional.empty());
        }

        /** The stretch selected, if any. */
        Optional<Range> selected() {
            return selection.map(Selection::range);
        }

        /** This island with {@code selected} as its stretch selected, or none; its ends' models folded once, here. */
        Island selecting(Optional<Range> selected) {
            if (selected.equals(selected())) {
                return this;
            }
            return new Island(tape, kept, endNanos, selected.map(r -> Selection.of(tape, kept, r)));
        }

        /** Its oldest end: as far back as the cursor can go. */
        long oldestNanos() {
            return tape.oldestNanos();
        }

        /** {@code atNanos}, kept between its ends. */
        long clamp(long atNanos) {
            return Math.clamp(atNanos, oldestNanos(), Math.max(endNanos, oldestNanos()));
        }

        /** This island with the rows of {@code batch} stamped inside it added; the same island if there are none. */
        Island late(List<Row> batch) {
            List<Row> inside = new ArrayList<>();
            for (Row r : batch) {
                if (r.atNanos() <= endNanos) {
                    inside.add(r);
                }
            }
            if (inside.isEmpty()) {
                return this;
            }
            Tape more = tape.add(inside, endNanos, FOREVER);
            Model keptMore = kept.fold(inside, endNanos, FOREVER);
            // A late row inside the stretch changes what it counted: it is read again from the fuller tape.
            Optional<Selection> again = selection.map(s -> inside.stream()
                    .anyMatch(r -> r.atNanos() <= s.range().toNanos() + ClockLedger.SLACK)
                    ? Selection.of(more, keptMore, s.range()) : s);
            return new Island(more, keptMore, endNanos, again);
        }
    }

    /**
     * A stretch selected, and what is counted over it that the tape must be read again for: the model as of each of
     * its ends, which the readouts' lost rows and every flow are counted between, and each clock's whole turns inside
     * it, summed, for the threads panel. Read when it is selected rather than for each message after.
     */
    public record Selection(Range range, Model from, Model to, List<Messages.Account> accounts) {

        public Selection {
            accounts = List.copyOf(accounts);
        }

        /** {@code r} on {@code tape}, its logic ticks those of the thread {@code kept}'s ran on. */
        static Selection of(Tape tape, Model kept, Range r) {
            long half = r.nanos() / 2;
            List<Row> rows = tape.near(r.fromNanos() + half, half + ClockLedger.SLACK, ClockLedger::reads);
            return new Selection(r, tape.asOf(r.fromNanos()), tape.asOf(r.toNanos()),
                    ClockLedger.over("", rows, r.fromNanos(), r.toNanos(), logicThread(kept)).accounts());
        }
    }

    /** How a run was reached, as the run panel says it: found or picked among the running ones. */
    public static final String ATTACHED = "attached";

    /** Started by this Inspector ({@code --launch}). */
    public static final String LAUNCHED = "launched";

    /** A recording played back ({@code --replay}). */
    public static final String REPLAYED = "replayed";

    /** What a recording's run is called: this, then its file's name. */
    static final String REPLAY = "replay:";

    /**
     * How the run was reached and what else there is: the run panel's half of the state.
     *
     * @param pid     the run's process; 0 for a recording or with no run
     * @param from    {@link #ATTACHED}, {@link #LAUNCHED} or {@link #REPLAYED}
     * @param chosen  whether the person chose the run (or {@code --attach}, {@code --launch}, {@code --replay} did), or
     *                detached: then the bench attaches to nothing by itself, and a run that goes away stays on screen
     * @param choices the runs that could be inspected, as the last listing found them
     * @param wish    what {@code --attach} named and has not been found yet: a run's name, or {@code pid:<n>}
     * @param replay  the recording {@code --replay} named, played when the first window opens; empty when none
     * @param launch  the command {@code --launch} named, started when the first window opens, until it can be inspected
     *                or has ended; empty when none
     * @param begun   whether the first window has opened, so what the command line named has been sent
     */
    public record Pick(long pid, String from, boolean chosen, List<Run> choices, String wish, String replay,
                       String launch, boolean begun) {

        public Pick {
            choices = List.copyOf(choices);
        }

        static Pick initial(String attach, String replay, String launch) {
            return new Pick(0L, "", !attach.isBlank() || !replay.isBlank() || !launch.isBlank(), List.of(),
                    attach.strip(), replay.strip(), launch.strip(), false);
        }

        Pick of(Run run, String from, boolean chosen) {
            return new Pick(run.pid(), from, chosen, choices, "", replay, "", begun);
        }

        Pick choices(List<Run> found) {
            return found.equals(choices) ? this : new Pick(pid, from, chosen, found, wish, replay, launch, begun);
        }

        Pick choose() {
            return chosen ? this : new Pick(pid, from, true, choices, wish, replay, launch, begun);
        }

        /** This, with what the command line asked to play or start sent: it is sent once. */
        Pick begin() {
            return begun ? this : new Pick(pid, from, chosen, choices, wish, replay, launch, true);
        }

        /** This, with the launched command ended. */
        Pick ended() {
            return launch.isEmpty() ? this : new Pick(pid, from, chosen, choices, wish, replay, "", begun);
        }

        /** The run of {@code runs} that {@link #wish} names, if any. */
        Optional<Run> wished(List<Run> runs) {
            for (Run r : runs) {
                if (r.name().equals(wish) || wish.equals("pid:" + r.pid())) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
    }

    /** The thread {@code kept}'s logic ticks ran on, as far as its rows tell; empty when none ran off main. */
    static String logicThread(Model kept) {
        for (Mark m : kept.lane(Model.LOGIC).marks()) {
            if (!m.thread().isEmpty()) {
                return m.thread();
            }
        }
        return "";
    }

    /**
     * Where {@code kept}'s logic ticks ran, as far as its rows tell, in words; empty before any row: its own lane's
     * thread, or the main thread, where the framework names the logic's ticks apart from the widgets' (FN-16).
     */
    static String logicOf(Model kept, boolean noRows) {
        String thread = logicThread(kept);
        if (thread.equals("main")) {
            return "logic on the main thread (--logic=main)";
        }
        if (!thread.isEmpty()) {
            return "logic on its own lane, " + thread;
        }
        if (noRows) {
            return "";
        }
        return kept.lane(Model.LOGIC).recorded() ? "no logic tick off the main thread: --logic=main, or idle"
                : "logic not recorded";
    }

    /**
     * Everything the bench knows, in one value.
     *
     * @param run      the run attached, or empty
     * @param title    its window's title, for the status line
     * @param status   what the status line says
     * @param arriving whether its rows are arriving
     * @param now      the model with every row read so far folded on
     * @param kept     the same, over {@link #RETAIN} rather than the panels' reach: every pass the lane can show
     * @param tape     the rows of the last {@link #RETAIN}, with their checkpoints
     * @param first    the moment of the run's first row read, which the status line counts from
     * @param paused   whether the cursor is stopped; following the present when not
     * @param cursor   where it is stopped, on the machine's clock
     * @param island   what it is stopped on; {@link Island#NONE} while following
     * @param on       the event the cursor is on, after a step by events or a click
     * @param row      the row the cursor is on, after a step by rows or a click on one; then it is on no event
     * @param gone     which run went away last, and why, kept to say beside "no application" until another is attached
     * @param pick     how the run was reached, whether it was chosen, and what else could be inspected
     */
    public record State(String run, String title, String status, boolean arriving, Model now, Model kept, Tape tape,
                        long first, boolean paused, long cursor, Island island, Optional<Event> on, Optional<Row> row,
                        String gone, Pick pick) {

        /** On no row. */
        State(String run, String title, String status, boolean arriving, Model now, Model kept, Tape tape, long first,
              boolean paused, long cursor, Island island, Optional<Event> on, String gone, Pick pick) {
            this(run, title, status, arriving, now, kept, tape, first, paused, cursor, island, on, Optional.empty(),
                    gone, pick);
        }

        static State initial(String attach, String replay, String launch) {
            String status = !replay.isBlank() ? "Opening " + replay : !launch.isBlank() ? "Launching " + launch
                    : attach.isBlank() ? "Looking for an application to inspect" : "Looking for " + attach + " to inspect";
            return new State("", "", status, false, Model.EMPTY, Model.EMPTY, Tape.EMPTY, 0L, false, 0L, Island.NONE,
                    Optional.empty(), "", Pick.initial(attach, replay, launch));
        }

        /** The run panel: the run, how it was reached, where its logic runs, and the runs that could be chosen. */
        RunPanel runPanel() {
            boolean none = run.isEmpty();
            return new RunPanel(run, title, none ? 0L : pick.pid(), none ? "" : pick.from(),
                    none ? "" : logicOf(kept, tape.isEmpty()), pick.choices(), pick.chosen());
        }

        State withPick(Pick pick) {
            return new State(run, title, status, arriving, now, kept, tape, first, paused, cursor, island, on, row,
                    gone, pick);
        }

        /** The timeline: every event kept, the cursor at the right edge while paused, the newest row while following. */
        Timeline timeline() {
            if (paused) {
                return new Timeline(run, status, false, cursor, island.kept().lanes(), on, kept.warning(),
                        island.selected());
            }
            return new Timeline(run, status, arriving, now.newestNanos(), kept.lanes(), Optional.empty(),
                    kept.warning());
        }

        /**
         * The readouts: over the stretch selected, from every event the island keeps, with the rows lost within it; or
         * else over the second before the cursor, {@code at} the model there.
         */
        Readouts readouts(Model at) {
            if (!paused || island.selection().isEmpty()) {
                return at.readouts(run);
            }
            Selection s = island.selection().get();
            Range r = s.range();
            long lost = s.to().lost() - s.from().lost();
            return island.kept().readouts(run, r.fromNanos(), r.toNanos(), true, Math.max(0L, lost));
        }

        /** The model as of the cursor: the one its island recomputes for it, or the live one while following. */
        Model atCursor() {
            return paused ? island.tape().asOf(cursor) : now;
        }

        /**
         * The declared graph and what crossed it as of the cursor, {@code at} the model there: written a second taken
         * from the tape's checkpoint a second or two before. Over a stretch selected, what crossed within it instead,
         * whatever the cursor. A recording declares no graph, and says so.
         */
        Flows flows(Model at) {
            boolean recording = run.startsWith(REPLAY);
            Optional<Selection> over = paused ? island.selection() : Optional.empty();
            Model end = over.map(Selection::to).orElse(at);
            String why = run.isEmpty() || end.declared().said() ? ""
                    : recording ? "a recording declares no bus graph: the topics its rows counted" : "";
            if (over.isPresent()) {
                return end.flowsOver(run, why, over.get().from(), over.get().range());
            }
            long moment = paused ? cursor : now.newestNanos();
            Tape.Second back = (paused ? island.tape() : tape).secondAt(moment - Tape.SECOND);
            return back == null ? at.flows(run, why, Model.EMPTY, 0L, moment)
                    : at.flows(run, why, back.start(), moment - back.fromNanos(), moment);
        }

        /**
         * The hops of the cause of the event the cursor is on, read from the tape when they are said, not when the
         * event was folded: the hops its cause takes go on after it. None while following, or on no event.
         */
        List<Model.Hop> hops() {
            return paused && on.isPresent()
                    ? Model.hopsOf(on.get().mark().cause(), island.tape().rowsOf(on.get().mark().cause())) : List.of();
        }

        /** The details of the cursor's moment, the newest row's while following, and of the event it is on. */
        Details details() {
            return details(hops());
        }

        /** As {@link #details()}, its event's path said from {@code hops}. */
        Details details(List<Model.Hop> hops) {
            long at = paused ? cursor : now.newestNanos();
            Optional<Event> event = paused ? on.map(e -> new Event(e.lane(), e.mark().with(Model.PATH,
                    island.kept().pathFrom(e.mark(), hops), false))) : Optional.empty();
            return new Details(run, at, tape.isEmpty() ? 0L : at - first, kept.clockAt(at), event,
                    paused ? row : Optional.empty());
        }

        /** What every panel marks of the path of the event the cursor is on, from {@code hops}; none while following. */
        Traced traced(List<Model.Hop> hops) {
            return paused && on.isPresent() ? Model.traced(run, on.get().mark(), hops) : Traced.NONE;
        }

        /**
         * The rows panel: the rows around the cursor, the newest at or before it selected; following, the newest rows.
         */
        RowsAround rowsAround() {
            if (tape.isEmpty() && island.tape().isEmpty()) {
                return new RowsAround(run, List.of(), -1);
            }
            return paused ? island.tape().around(run, row.orElse(Tape.key(cursor)), AROUND) : tape.newest(run, AROUND);
        }

        /**
         * The ledger of each clock's turn the cursor is in; following, of the newest whole turns; over a stretch
         * selected, of each clock's whole turns inside it, summed, wherever the cursor is.
         */
        LedgerAt ledger() {
            if (tape.isEmpty() && island.tape().isEmpty()) {
                return new LedgerAt(run, List.of());
            }
            if (paused && island.selection().isPresent()) {
                return new LedgerAt(run, island.selection().get().accounts());
            }
            String logic = logicThread(kept);
            if (paused) {
                return ClockLedger.at(run, island.tape().near(cursor, ClockLedger.SPAN, ClockLedger::reads), cursor,
                        logic);
            }
            // Following, on every batch: the newest rows only, read back a widening window until the main thread's and
            // drawing's turns are whole in it, rather than the seconds either side.
            long at = now.newestNanos();
            for (long back = ClockLedger.FIRST_LOOK; ; back *= 4) {
                LedgerAt l = ClockLedger.at(run, tape.since(at - back, ClockLedger::reads), at, logic);
                if (back >= ClockLedger.SPAN || l.accounts().stream().filter(a -> !a.clock().equals(Model.LOGIC))
                        .filter(a -> !a.clock().equals(ClockLedger.JOBS)).noneMatch(a -> a.turn().isEmpty())) {
                    return l;
                }
            }
        }

        /** The island the cursor is on, or, following, the one stopping now at {@code atNanos} would freeze. */
        Island held(long atNanos) {
            return paused ? island : Island.of(this, atNanos);
        }

        State withStatus(String status) {
            return new State(run, title, status, arriving, now, kept, tape, first, paused, cursor, island, on, row,
                    gone, pick);
        }

        State following(String status) {
            return new State(run, title, status, arriving, now, kept, tape, first, false, 0L, Island.NONE,
                    Optional.empty(), gone, pick);
        }

        State pausedAt(Island island, long at, String status) {
            return new State(run, title, status, arriving, now, kept, tape, first, true, at, island, Optional.empty(),
                    gone, pick);
        }

        State pausedOn(Island island, Event event, String status) {
            return new State(run, title, status, arriving, now, kept, tape, first, true, event.mark().toNanos(),
                    island, Optional.of(event), gone, pick);
        }

        State pausedOnRow(Island island, Row r, String status) {
            return new State(run, title, status, arriving, now, kept, tape, first, true, r.atNanos(), island,
                    Optional.empty(), Optional.of(r), gone, pick);
        }
    }

    private final Ledger<State> ledger;

    /**
     * @param attach {@code --attach=<name|pid:n>}: the run to inspect, waited for until it is running; empty to attach
     *               to the newest one there is
     * @param replay {@code --replay=<csv>}: a probe recording to play back instead; empty for none
     * @param launch {@code --launch="<command>"}: a command to start and inspect instead; empty for none
     */
    public Bench(Inbox inbox, Timer timer, @Setting("attach") String attach, @Setting("replay") String replay,
                 @Setting("launch") String launch) {
        Ledger.Builder<State> b = Ledger.of(State.initial(attach, replay, launch));
        b.on(WindowOpened.TOPIC, b.step("begin", Bench::begin))
                .on(Messages.RUNS_TOPIC, b.step("runs", Bench::runs))
                .on(Messages.ROWS_TOPIC, b.step("rows", Bench::rows))
                .on(Messages.DETACHED_TOPIC, b.step("detached", Bench::detached))
                .on(Messages.CHILD_EXITED_TOPIC, b.step("child exited", Bench::childExited))
                .on(Messages.CHOOSE_TOPIC, b.step("choose", Bench::choose))
                .on(Messages.LEAVE_TOPIC, b.step("leave", Bench::leave))
                .on(Messages.PAUSE_TOPIC, b.step("pause", Bench::pause))
                .on(Messages.FOLLOW_TOPIC, b.step("follow", Bench::follow))
                .on(Messages.STEP_TOPIC, b.step("step", Bench::step))
                .on(Messages.STEP_EVENT_TOPIC, b.step("step event", Bench::stepEvent))
                .on(Messages.GO_TO_TOPIC, b.step("go to", Bench::goTo))
                .on(Messages.STEP_ROW_TOPIC, b.step("step row", Bench::stepRow))
                .on(Messages.GO_TO_ROW_TOPIC, b.step("go to row", Bench::goToRow))
                .on(Messages.GO_TO_TICK_TOPIC, b.step("go to tick", Bench::goToTick))
                .on(Messages.SEEK_TOPIC, b.step("seek", Bench::seek))
                .on(Messages.SELECT_RANGE_TOPIC, b.step("select range", Bench::selectRange))
                .on(Messages.CLEAR_RANGE_TOPIC, b.step("clear range", Bench::clearRange))
                .on(Messages.EXPORT_FRAME_TOPIC, b.step("export frame", Bench::exportFrame))
                .on(Picked.TOPIC, b.step("picked", Bench::picked))
                .on(Messages.EXPORTED_TOPIC, b.step("exported", Bench::exported));
        ledger = b.build(inbox, "bench", CAPACITY);
        timer.every(LOOK_AGAIN, Messages.FIND_RUNS_TOPIC, new FindRuns());
    }

    /** The bench's state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    // ------------------------------------------------------------------ the steps

    /**
     * The main window exists: look for a run at once, rather than at the timer's first beat, and play or start what the
     * command line named.
     */
    static State begin(State s, WindowOpened opened, Out out) {
        out.send(Messages.FIND_RUNS_TOPIC, new FindRuns());
        Pick pick = s.pick();
        if (pick.begun()) {
            return s;
        }
        pick = pick.begin();
        if (!pick.replay().isEmpty()) {
            String file = java.nio.file.Path.of(pick.replay()).getFileName().toString();
            String run = REPLAY + file;
            out.send(Messages.REPLAY_TOPIC, new Replay(run, pick.replay()));
            Run recording = new Run(run, file, 0L, "");
            return say(new State(run, file, "Replaying " + file, false, Model.EMPTY, Model.EMPTY, Tape.EMPTY, 0L,
                    false, 0L, Island.NONE, Optional.empty(), "", pick.of(recording, REPLAYED, true)), out);
        }
        if (!pick.launch().isEmpty()) {
            out.send(Messages.LAUNCH_CHILD_TOPIC, new LaunchChild(pick.launch()));
            return say(s.withPick(pick), out);
        }
        out.send(Messages.TIMELINE_TOPIC, s.timeline());
        out.send(Messages.PAUSED_TOPIC, new Paused(s.paused()));
        return panel(s.withPick(pick), out);
    }

    /**
     * A launched command has ended. Its run, if attached, goes away by its own link's word; this says why the command
     * ended while nothing else is shown.
     */
    static State childExited(State s, ChildExited c, Out out) {
        String ended = c.command() + ": " + c.why();
        if (!s.run().isEmpty()) {
            return s.withPick(s.pick().ended());
        }
        return say(new State("", "", ended, false, s.now(), s.kept(), s.tape(), s.first(), s.paused(), s.cursor(),
                s.island(), s.on(), s.row(), ended, s.pick().ended()), out);
    }

    /**
     * Which runs there are, for the picker. While none is attached: the one {@code --attach} named once it is running;
     * otherwise, unless a run was chosen (or detached from), the newest, letting the last one's tape go.
     */
    static State runs(State s, Runs found, Out out) {
        Pick pick = s.pick().choices(found.runs());
        State listed = s.withPick(pick);
        if (!s.run().isEmpty()) {
            return pick == s.pick() ? s : panel(listed, out);
        }
        if (!pick.wish().isEmpty()) {
            Optional<Run> wished = pick.wished(found.runs());
            if (wished.isPresent()) {
                return attach(listed, wished.get(), ATTACHED, true, out);
            }
        }
        if (found.runs().isEmpty() || pick.chosen()) {
            String status = s.gone().isEmpty() ? waiting(pick, found) : s.gone() + ". " + waiting(pick, found);
            if (status.equals(s.status()) && pick == s.pick()) {
                return s;
            }
            return say(listed.withStatus(status), out);
        }
        return attach(listed, found.runs().getLast(), ATTACHED, false, out);
    }

    /** What the status line says while nothing is attached, and the bench is not about to attach to anything. */
    private static String waiting(Pick pick, Runs found) {
        if (!found.problem().isEmpty()) {
            return found.problem();
        }
        if (!pick.wish().isEmpty()) {
            return "Waiting for " + pick.wish() + " to start";
        }
        if (!pick.launch().isEmpty()) {
            return "Launching " + pick.launch();
        }
        return found.runs().isEmpty() ? NONE : "Choose an application to inspect";
    }

    /**
     * The person, {@code --attach} or a launched child chose a run: attach to it, stopping the one attached, and stay
     * with it. Already attached to it, only remember that it was chosen.
     */
    static State choose(State s, Choose c, Out out) {
        if (c.run().name().equals(s.run())) {
            return panel(s.withPick(s.pick().choose()), out);
        }
        if (!s.run().isEmpty()) {
            out.send(Messages.DETACH_TOPIC, new Detach(s.run()));
        }
        return attach(s, c.run(), c.from(), true, out);
    }

    /**
     * The Detach button: stop reading the run attached, keep its tape to step through, as when a run goes away, and
     * attach to nothing until one is chosen. Its link's {@link Detached} reply comes after, for a run no longer attached.
     */
    static State leave(State s, Leave l, Out out) {
        if (s.run().isEmpty()) {
            return s;
        }
        out.send(Messages.DETACH_TOPIC, new Detach(s.run()));
        String gone = "Detached from " + named(s.title(), s.run());
        return say(new State("", "", gone, false, s.now(), s.kept(), s.tape(), s.first(), s.paused(), s.cursor(),
                s.island(), s.on(), s.row(), gone, s.pick().choose()), out);
    }

    /** Attach to {@code run}, reached {@code from}, and let the last run's tape go. */
    private static State attach(State s, Run run, String from, boolean chosen, Out out) {
        out.send(Messages.ATTACH_TOPIC, new Attach(run.name(), run.stream()));
        // The banner stays up while attaching again, so a run its link cannot read does not flicker it every beat; a
        // stream that opens says at once whether there is anything to warn of.
        return say(new State(run.name(), run.title(), "Attaching to " + named(run.title(), run.name()), false,
                Model.EMPTY, Model.EMPTY.withWarning(s.kept().warning()), Tape.EMPTY, 0L, false, 0L, Island.NONE,
                Optional.empty(), "", s.pick().of(run, from, chosen)), out);
    }

    /**
     * A batch of the run's rows: folded onto the live model and kept. Shown at once while following; while paused,
     * added to the island if stamped inside it, and shown only if a row of it is before the cursor, since the rest are
     * past the lane's right edge.
     */
    static State rows(State s, Rows batch, Out out) {
        if (!batch.run().equals(s.run())) {
            return s;   // a late batch of a run since gone
        }
        Model now = s.now().fold(batch.rows(), Long.MAX_VALUE);
        Model kept = s.kept().fold(batch.rows(), Long.MAX_VALUE, RETAIN);
        Tape tape = s.tape().add(batch.rows(), now.newestNanos(), RETAIN);
        long first = s.tape().isEmpty() && !batch.rows().isEmpty() ? batch.rows().getFirst().atNanos() : s.first();
        if (s.paused()) {
            State next = new State(s.run(), s.title(), s.status(), true, now, kept, tape, first, true, s.cursor(),
                    s.island().late(batch.rows()), s.on(), s.row(), "", s.pick());
            for (Row r : batch.rows()) {
                if (r.atNanos() <= s.cursor()) {
                    return say(next, out);
                }
            }
            return next;
        }
        String status = (batch.lostTo() > 0 ? "Lost rows " + batch.lostFrom() + ".." + batch.lostTo() + " of "
                : "Inspecting ") + named(s.title(), s.run());
        return say(new State(s.run(), s.title(), status, true, now, kept, tape, first, false, 0L, Island.NONE,
                Optional.empty(), "", s.pick()), out);
    }

    /**
     * The run's link has ended: say why and keep its tape to step through. A run that was found is replaced by the
     * timer's next find; a chosen one stays on screen until another is chosen.
     */
    static State detached(State s, Detached d, Out out) {
        if (!d.run().equals(s.run())) {
            return s;
        }
        String gone = named(s.title(), s.run()) + ": " + d.why();
        Model kept = d.warning().isEmpty() ? s.kept() : s.kept().withWarning(d.warning());
        return say(new State("", "", gone, false, s.now(), kept, s.tape(), s.first(), s.paused(), s.cursor(),
                s.island(), s.on(), s.row(), gone, s.pick()), out);
    }

    /** Space: following, stop where the view's lane comes to rest; paused, follow the present again. */
    static State pause(State s, Pause p, Out out) {
        if (s.tape().isEmpty()) {
            return s;
        }
        if (s.paused()) {
            return say(s.following(following(s)), out);
        }
        Island island = s.held(p.restNanos());
        return say(paused(s, island, island.clamp(p.restNanos()), false), out);
    }

    /** End, or the button: follow the present again, letting the island go. Following already, nothing. */
    static State follow(State s, Follow f, Out out) {
        if (!s.paused()) {
            return s;
        }
        return say(s.following(following(s)), out);
    }

    /**
     * Shift and an arrow: move the cursor by time, from where it is stopped, or from where the view's lane would rest
     * if following; never off its island.
     */
    static State step(State s, Step by, Out out) {
        if (s.tape().isEmpty()) {
            return s;
        }
        long from = s.paused() ? s.cursor() : by.fromNanos();
        Island island = s.held(from);
        long want = from + by.byNanos();
        return say(paused(s, island, island.clamp(want), want > island.endNanos()), out);
    }

    /**
     * An arrow: move the cursor onto the next or previous event of the lane the view has focused, {@code by} times,
     * and say which. Back from a moment the cursor is not on an event of that lane (following, stepped there by time,
     * or on another lane's), the first step is onto its event ending at or before it. Only events on its island; at
     * either end it stays where it is.
     */
    static State stepEvent(State s, StepEvent e, Out out) {
        if (s.tape().isEmpty() || !Model.LANES.contains(e.lane())) {
            return s;
        }
        long at = s.paused() ? s.cursor() : e.fromNanos();
        Island island = s.held(at);
        List<Mark> marks = island.kept().lane(e.lane()).marks();
        boolean onOne = s.paused() && s.on().isPresent() && s.on().get().lane().equals(e.lane());
        Mark to = null;
        for (int i = 0; i < Math.abs(e.by()); i++) {
            Mark next = e.by() < 0 ? before(island, marks, at, onOne || to != null) : after(island, marks, at);
            if (next == null) {
                break;
            }
            to = next;
            at = next.toNanos();
        }
        return to == null ? s : say(on(s, island, new Event(e.lane(), to)), out);
    }

    /** A click on an event: the cursor onto it, if it is one on the island. */
    static State goTo(State s, GoTo g, Out out) {
        Event e = g.event();
        Island island = s.held(e.mark().toNanos());
        if (!Model.LANES.contains(e.lane()) || island.clamp(e.mark().toNanos()) != e.mark().toNanos()) {
            return s;
        }
        // The same event as the tape keeps it: the rows after a pass fill fields of it the view may not have seen yet.
        Mark m = e.mark();
        for (Mark kept : island.kept().lane(e.lane()).marks()) {
            if (kept.fromNanos() == m.fromNanos() && kept.toNanos() == m.toNanos() && kept.kind().equals(m.kind())) {
                return say(on(s, island, new Event(e.lane(), kept)), out);
            }
        }
        return s;
    }

    /**
     * A difference clicked in the replay panel: the cursor onto the logic tick it names, as a click on it would put it
     * there. A tick no longer on the tape, or never numbered, leaves the cursor where it is.
     */
    static State goToTick(State s, GoToTick g, Out out) {
        String number = "#" + g.number();
        for (Mark m : s.kept().lane(Model.LOGIC).marks()) {
            if (m.kind().equals(Model.TICK) && m.field(Model.TICK_NUMBER).equals(number)) {
                return goTo(s, new GoTo(new Event(Model.LOGIC, m)), out);
            }
        }
        return s;
    }

    /**
     * Up or Down: move the cursor onto the next or previous row, {@code by} times, in the rows panel's order, and say
     * which. From the row it is on, or from its moment when on none, so that the first step back is onto the row the
     * panel selects there. Only rows on its island; at either end it stays where it is.
     */
    static State stepRow(State s, StepRow r, Out out) {
        if (s.tape().isEmpty() && s.island().tape().isEmpty()) {
            return s;
        }
        long at = s.paused() ? s.cursor() : r.fromNanos();
        Island island = s.held(at);
        Row from = s.paused() && s.row().isPresent() ? s.row().get() : Tape.key(at);
        Row to = null;
        for (int i = 0; i < Math.abs(r.by()); i++) {
            Row next = island.tape().next(from, r.by() < 0);
            if (next == null || island.clamp(next.atNanos()) != next.atNanos()) {
                break;
            }
            to = next;
            from = next;
        }
        return to == null ? s : say(onRow(s, island, to), out);
    }

    /** A click on a row of the rows panel: the cursor onto it, if it is on the island. */
    static State goToRow(State s, GoToRow g, Out out) {
        if (s.tape().isEmpty() && s.island().tape().isEmpty()) {
            return s;
        }
        Island island = s.held(g.row().atNanos());
        if (island.clamp(g.row().atNanos()) != g.row().atNanos()) {
            return s;
        }
        return say(onRow(s, island, g.row()), out);
    }

    /** A click where no event is near: the cursor at that moment, kept on the island. */
    static State seek(State s, Seek k, Out out) {
        if (s.tape().isEmpty()) {
            return s;
        }
        Island island = s.held(Long.MIN_VALUE);
        return say(paused(s, island, island.clamp(k.atNanos()), k.atNanos() > island.endNanos()), out);
    }

    /**
     * Shift and a drag: select the stretch between its two moments, kept on the island, and put the cursor at its later
     * end, pausing if following. One with no length once kept there selects nothing, and leaves what was.
     */
    static State selectRange(State s, SelectRange r, Out out) {
        if (s.tape().isEmpty() && s.island().tape().isEmpty()) {
            return s;
        }
        Island island = s.held(Math.max(r.fromNanos(), r.toNanos()));
        long from = island.clamp(Math.min(r.fromNanos(), r.toNanos()));
        long to = island.clamp(Math.max(r.fromNanos(), r.toNanos()));
        if (to <= from) {
            return s;
        }
        Range range = new Range(from, to);
        String where = String.format(Locale.ROOT, "Selected %.3f s, from %.3f s to %.3f s", range.nanos() / 1e9,
                (from - s.first()) / 1e9, (to - s.first()) / 1e9)
                + (s.run().isEmpty() ? "" : " of " + named(s.title(), s.run())) + ". Escape lets it go";
        return say(s.pausedAt(island.selecting(Optional.of(range)), to,
                s.run().isEmpty() ? s.gone() + ". " + where : where), out);
    }

    /** Escape: let the stretch selected go, the cursor staying where it is. Nothing selected, nothing. */
    static State clearRange(State s, ClearRange c, Out out) {
        if (!s.paused() || s.island().selected().isEmpty()) {
            return s;
        }
        return say(paused(s, s.island().selecting(Optional.empty()), s.cursor(), false), out);
    }

    // ------------------------------------------------------------------ exporting the frame

    /** The id the bench's save picker is asked and answered by, so another part's {@link Picked} is not taken as it. */
    static final long EXPORT_PICK = 0x1E5_E0F7L;

    /** How far either side of the cursor the frame's rows are looked for: a pass longer than this is cut. */
    static final long FRAME_REACH = 2 * Tape.SECOND;

    /**
     * Export frame: following, pause first where the view's lane comes to rest, as Space does; then ask where to save.
     * The frame is read when the answer comes ({@link #picked}): the picker is modal over the window, so the cursor
     * cannot move while it is up. Nothing kept, nothing to export.
     */
    static State exportFrame(State s, ExportFrame e, Out out) {
        if (s.tape().isEmpty() && s.island().tape().isEmpty()) {
            return s;
        }
        State at = s;
        if (!s.paused()) {
            Island island = s.held(e.restNanos());
            at = say(paused(s, island, island.clamp(e.restNanos()), false), out);
        }
        out.send(PickFile.TOPIC, PickFile.save(EXPORT_PICK, "", exportName(at),
                List.of(new PickFile.Filter("CSV", List.of("csv")))));
        return at;
    }

    /** Where to save the frame, or that the person cancelled: write it there, as of the cursor now. */
    static State picked(State s, Picked p, Out out) {
        if (p.id() != EXPORT_PICK || p.isCancelled()) {
            return s;
        }
        out.send(Messages.WRITE_EXPORT_TOPIC, new WriteExport(p.path(), frameCsv(s)));
        return s;
    }

    /** The frame written, or not: the status line says where, or why. */
    static State exported(State s, Exported e, Out out) {
        State next = s.withStatus(e.problem().isEmpty() ? "Exported the frame to " + e.path()
                : "Could not export the frame to " + e.path() + ": " + e.problem());
        out.send(Messages.TIMELINE_TOPIC, next.timeline());
        return next;
    }

    /**
     * The cursor's frame, the main loop's pass its rows number: the event's it is on, or the row's, or else the
     * newest row's at or before it. 0 when the rows number none there.
     */
    static long frameOf(State s, RowsAround around) {
        if (s.paused() && s.on().isPresent()) {
            return s.on().get().mark().frame();
        }
        if (s.paused() && s.row().isPresent()) {
            return s.row().get().frame();
        }
        return around.selected() < 0 ? 0L : around.rows().get(around.selected()).frame();
    }

    /** Everything the panels show as of the cursor, and every row of its frame, as {@link FrameCsv} lays it out. */
    static String frameCsv(State s) {
        Model at = s.atCursor();
        List<Model.Hop> hops = s.hops();
        Details details = s.details(hops);
        RowsAround around = s.rowsAround();
        long frame = frameOf(s, around);
        List<Row> rows = new ArrayList<>();
        if (frame > 0) {
            Tape tape = s.paused() ? s.island().tape() : s.tape();
            rows.addAll(tape.near(details.atNanos(), FRAME_REACH, r -> r.frame() == frame));
            rows.sort(Tape.ORDER);
        }
        return FrameCsv.of(named(s.title(), s.run()), s.first(), frame, details, s.readouts(at), s.traced(hops),
                s.ledger(), s.flows(at), around, rows);
    }

    /** What the picker offers to save as: the run and the frame, with nothing a file name cannot hold. */
    static String exportName(State s) {
        long frame = frameOf(s, s.rowsAround());
        String run = s.run().isEmpty() ? "inspector" : s.run();
        return (run + (frame > 0 ? " frame " + frame : " frame")).replaceAll("[\\\\/:*?\"<>|]", "-") + ".csv";
    }

    /** The event ending latest before {@code at} (or at it, unless {@code strictly}) on the island. */
    private static Mark before(Island island, List<Mark> marks, long at, boolean strictly) {
        Mark best = null;
        for (Mark p : marks) {
            boolean earlier = strictly ? p.toNanos() < at : p.toNanos() <= at;
            if (earlier && p.toNanos() >= island.oldestNanos() && (best == null || p.toNanos() > best.toNanos())) {
                best = p;
            }
        }
        return best;
    }

    /** The event ending soonest after {@code at} on the island. */
    private static Mark after(Island island, List<Mark> marks, long at) {
        Mark best = null;
        for (Mark p : marks) {
            if (p.toNanos() > at && p.toNanos() <= island.endNanos()
                    && (best == null || p.toNanos() < best.toNanos())) {
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ what the status line says

    /** What the status line adds when a move stops at the island's newest end: nothing further, and how to get back. */
    static final String AT_THE_END = ", the newest moment kept when paused. End follows the present again";

    private static State on(State s, Island island, Event e) {
        Mark m = e.mark();
        String type = m.field(Model.TYPE);
        String kind = Character.toUpperCase(m.kind().charAt(0)) + m.kind().substring(1)
                + (type.isEmpty() || type.equals(m.kind()) ? "" : " " + type)
                + (m.kind().equals(Model.PASS) && m.frame() > 0 ? " #" + m.frame() : "");
        String what = m.toNanos() == m.fromNanos()
                ? String.format(Locale.ROOT, "%s at %.3f s", kind, (m.toNanos() - s.first()) / 1e9)
                : String.format(Locale.ROOT, "%s of %.2f ms, ending at %.3f s", kind,
                        (m.toNanos() - m.fromNanos()) / 1e6, (m.toNanos() - s.first()) / 1e9);
        String status = s.run().isEmpty() ? s.gone() + ". " + what : what + " of " + named(s.title(), s.run());
        return s.pausedOn(island, e, status);
    }

    /** On row {@code r}, which the status line says. */
    private static State onRow(State s, Island island, Row r) {
        String what = String.format(Locale.ROOT, "Row %s, %s on %s, at %.6f s",
                r.seq() < 0 ? "added by the link" : "#" + r.seq(), r.kind(), r.lane().isEmpty() ? "no lane" : r.lane(),
                (r.atNanos() - s.first()) / 1e9);
        String status = s.run().isEmpty() ? s.gone() + ". " + what : what + " of " + named(s.title(), s.run());
        return s.pausedOnRow(island, r, status);
    }

    /** Stopped at {@code at}; {@code pastTheEnd} when it was asked to go beyond the island's newest end. */
    private static State paused(State s, Island island, long at, boolean pastTheEnd) {
        String where = String.format(Locale.ROOT, "Paused at %.2f s", (at - s.first()) / 1e9)
                + (s.run().isEmpty() ? "" : " of " + named(s.title(), s.run()))
                + (at == island.oldestNanos() ? ", the oldest moment kept" : pastTheEnd ? AT_THE_END : "");
        return s.pausedAt(island, at, s.run().isEmpty() ? s.gone() + ". " + where : where);
    }

    private static String following(State s) {
        if (s.run().isEmpty()) {
            return s.gone();
        }
        return (s.arriving() ? "Inspecting " : "Attaching to ") + named(s.title(), s.run());
    }

    /** Tell the view the timeline, readouts, details, path, flows, rows and ledger, all as of the cursor. */
    private static State say(State s, Out out) {
        out.send(Messages.TIMELINE_TOPIC, s.timeline());
        out.send(Messages.PAUSED_TOPIC, new Paused(s.paused()));
        Model at = s.atCursor();
        out.send(Messages.READOUTS_TOPIC, s.readouts(at));
        out.send(Messages.FLOWS_TOPIC, s.flows(at));
        List<Model.Hop> hops = s.hops();
        out.send(Messages.DETAILS_TOPIC, s.details(hops));
        out.send(Messages.TRACED_TOPIC, s.traced(hops));
        out.send(Messages.ROWS_AROUND_TOPIC, s.rowsAround());
        out.send(Messages.LEDGER_TOPIC, s.ledger());
        return panel(s, out);
    }

    /** Tell the view the run panel. */
    private static State panel(State s, Out out) {
        out.send(Messages.RUN_PANEL_TOPIC, s.runPanel());
        return s;
    }

    private static String named(String title, String run) {
        return title.isBlank() ? run : title + " (" + run + ")";
    }
}
