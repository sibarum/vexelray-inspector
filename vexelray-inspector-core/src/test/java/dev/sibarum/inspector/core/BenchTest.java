package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.ChildExited;
import dev.sibarum.inspector.core.Messages.Choose;
import dev.sibarum.inspector.core.Messages.Detach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Details;
import dev.sibarum.inspector.core.Messages.Event;
import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Follow;
import dev.sibarum.inspector.core.Messages.GoTo;
import dev.sibarum.inspector.core.Messages.Lane;
import dev.sibarum.inspector.core.Messages.LaunchChild;
import dev.sibarum.inspector.core.Messages.Leave;
import dev.sibarum.inspector.core.Messages.Pause;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.RunPanel;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.Readouts;
import dev.sibarum.inspector.core.Messages.Replay;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Messages.Step;
import dev.sibarum.inspector.core.Messages.StepEvent;
import dev.sibarum.inspector.core.Messages.Timeline;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WindowOpened;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench, alone: no window, no link. It owns an inbox on a tick of this test's, as it will own one on the
 * framework's; each test publishes what the job, the source or the framework would, runs the tick, and reads what the
 * bench sent. The source's batches are made by hand, which is what makes a late batch or a lost range as easy to test
 * as the common case.
 */
class BenchTest {

    private static final long MS = 1_000_000L;
    private static final Run ONE = new Run("calculator-11", "Calculator", 11, "uds:one");
    private static final Run TWO = new Run("scheduler-12", "Scheduler", 12, "uds:two");

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Timer timer;
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        build("");
    }

    /** A bench of its own on a bus of its own, as launched with {@code --attach=<attach>}. */
    private void build(String attach) {
        build(attach, "", "");
    }

    /** A bench of its own, as launched with {@code --attach}, {@code --replay} and {@code --launch}. */
    private void build(String attach, String replay, String launch) {
        if (timer != null) {
            timer.close();
        }
        sent.clear();
        bus = Atchung.create();
        tick = bus.tick(null);
        timer = new Timer(bus);
        new Bench(tick.inbox("bench"), timer, attach, replay, launch);
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(Messages.FIND_RUNS_TOPIC, Messages.ATTACH_TOPIC, Messages.DETACH_TOPIC,
                Messages.TIMELINE_TOPIC, Messages.READOUTS_TOPIC, Messages.DETAILS_TOPIC, Messages.RUN_PANEL_TOPIC,
                Messages.REPLAY_TOPIC, Messages.LAUNCH_CHILD_TOPIC, Messages.FLOWS_TOPIC)) {
            listen(heard, t);
        }
    }

    @AfterEach
    void close() {
        timer.close();
        Atchung.onFatal(previous);
    }

    private <T> void listen(Inbox heard, Topic<T> topic) {
        heard.subscribe(topic, sent::add, 256);
    }

    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    /** What was sent of one kind, in order; everything sent so far is forgotten. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    /** The last timeline sent; everything sent so far is forgotten. */
    private Timeline timeline() {
        Timeline t = took(Timeline.class).getLast();
        return new Timeline(t.run(), t.status(), t.live(), t.edgeNanos(), t.lanes().stream()
                .map(l -> new Lane(l.name(), l.marks().stream().map(BenchTest::bare).toList())).toList(),
                t.on().map(BenchTest::bare), t.warning());
    }

    /** The last readouts sent, keeping the rest of what was sent. */
    private Readouts readouts() {
        return sent.stream().filter(Readouts.class::isInstance).map(Readouts.class::cast).toList().getLast();
    }

    /** The last details sent, keeping the rest of what was sent. */
    private Details details() {
        Details d = sent.stream().filter(Details.class::isInstance).map(Details.class::cast).toList().getLast();
        return new Details(d.run(), d.atNanos(), d.intoNanos(), d.clockNanos(), d.on().map(BenchTest::bare));
    }

    /** A timeline's main-thread lane: its passes. */
    private static List<Mark> passes(Timeline t) {
        return lane(t, Model.MAIN);
    }

    /** A lane's events as when, what kind and on which thread: their fields have tests of their own. */
    private static List<Mark> lane(Timeline t, String name) {
        return t.lanes().get(Model.LANES.indexOf(name)).marks().stream().map(BenchTest::bare).toList();
    }

    private static Mark bare(Mark m) {
        return new Mark(m.fromNanos(), m.toNanos(), m.kind(), m.thread());
    }

    private static Event bare(Event e) {
        return new Event(e.lane(), bare(e.mark()));
    }

    /** The cursor on a pass. */
    private static Optional<Event> on(Mark pass) {
        return Optional.of(new Event(Model.MAIN, pass));
    }

    /** A pass of the run's main loop: the frame zone's row at its end, carrying how long it took. */
    private static Row pass(long seq, long endNanos, String took) {
        return new Row(seq, endNanos, "main", "frame", "frame", took);
    }

    private static Row row(long seq, long at, String thread, String lane, String kind, String detail) {
        return new Row(seq, at, thread, lane, kind, detail);
    }

    private void attached(Run run) {
        tell(Messages.RUNS_TOPIC, new Runs(List.of(run), ""));
        sent.clear();
    }

    @Test
    void theFirstWindowLooksForARunAtOnce() {
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        assertEquals(List.of(new FindRuns()), took(FindRuns.class));
    }

    @Test
    void theNewestRunIsAttachedTo() {
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        assertEquals(List.of(new Attach("scheduler-12", "uds:two")), took(Attach.class));
        sent.clear();
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        assertEquals(List.of(), took(Attach.class), "attached already: the timer's next beat changes nothing");
    }

    @Test
    void withNoRunItSaysHowToStartOneOnce() {
        tell(Messages.RUNS_TOPIC, new Runs(List.of(), ""));
        Timeline t = timeline();
        assertEquals(Bench.NONE, t.status());
        assertFalse(t.live());
        tell(Messages.RUNS_TOPIC, new Runs(List.of(), ""));
        assertEquals(List.of(), took(Timeline.class), "said once, not every beat");
    }

    @Test
    void aRunChosenIsAttachedToAndTheOneAttachedIsStopped() {
        attached(ONE);
        tell(Messages.CHOOSE_TOPIC, new Choose(TWO, Bench.ATTACHED));
        assertEquals(List.of(new Detach(ONE.name())), took(Detach.class));
        tell(Messages.CHOOSE_TOPIC, new Choose(TWO, Bench.ATTACHED));
        assertEquals(List.of(), took(Attach.class), "chosen again: it is attached already");
        tell(Messages.CHOOSE_TOPIC, new Choose(ONE, Bench.ATTACHED));
        assertEquals(List.of(new Attach(ONE.name(), ONE.stream())), took(Attach.class));
    }

    @Test
    void aChosenRunThatGoesAwayStaysOnScreenAndNoOtherIsAttached() {
        tell(Messages.CHOOSE_TOPIC, new Choose(ONE, Bench.ATTACHED));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 100 * MS, "4ms"))));
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "it went away"));
        sent.clear();
        tell(Messages.RUNS_TOPIC, new Runs(List.of(TWO), ""));
        assertTrue(sent.stream().noneMatch(Attach.class::isInstance), "chosen: not left for another by itself");
        Timeline t = timeline();
        assertEquals(1, passes(t).size(), "its tape stays to step through");
        assertEquals("Calculator (calculator-11): it went away. Choose an application to inspect", t.status());
    }

    @Test
    void aFoundRunThatGoesAwayIsReplacedByTheNextFound() {
        attached(ONE);
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "it went away"));
        sent.clear();
        tell(Messages.RUNS_TOPIC, new Runs(List.of(TWO), ""));
        assertEquals(List.of(new Attach(TWO.name(), TWO.stream())), took(Attach.class));
    }

    @Test
    void detachingStopsTheRunKeepsItsTapeAndAttachesToNothing() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 100 * MS, "4ms"))));
        sent.clear();
        tell(Messages.LEAVE_TOPIC, new Leave());
        assertEquals(List.of(new Detach(ONE.name())), took(Detach.class));
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "stopped"));
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        assertTrue(sent.stream().noneMatch(Attach.class::isInstance));
        Timeline t = timeline();
        assertEquals(1, passes(t).size());
        assertEquals("Detached from Calculator (calculator-11). Choose an application to inspect", t.status());
    }

    @Test
    void attachWaitsForTheRunItNamesByNameOrPid() {
        build("pid:12");
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        assertEquals(List.of(), took(Attach.class), "not the newest: the one named");
        tell(Messages.RUNS_TOPIC, new Runs(List.of(), ""));
        assertEquals("Waiting for pid:12 to start", timeline().status());
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        assertEquals(List.of(new Attach(TWO.name(), TWO.stream())), took(Attach.class));

        build("calculator-11");
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        assertEquals(List.of(new Attach(ONE.name(), ONE.stream())), took(Attach.class));
    }

    @Test
    void theFlowsAreSaidWithTheTimelineAsOfTheCursor() {
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        long at = 2_000 * MS;
        String m = "dev.sibarum.inspector.core.Messages.";
        String graph = "# vexelray bus graph 2\npart component dev.example.Bench lane=logic\n"
                + "sends dev.example.Bench topic=" + m + "Timeline\ntopic " + m + "Timeline kind=state";
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(
                new Row(-1, at, "", Model.APP_LANE, Model.GRAPH, graph),
                new Row(1, at, 0L, 3L, "vexel-logic", "bus", "send", m + "Timeline from dev.example.Bench 1", ""),
                new Row(2, at + 400 * MS, 0L, 3L, "vexel-logic", "bus", "send", m + "Timeline from dev.example.Bench 1",
                        ""))));
        Flows f = took(Flows.class).getLast();
        assertEquals(ONE.name(), f.run());
        assertEquals("", f.why());
        assertEquals(List.of(new Part("component", "dev.example.Bench", "logic", false)), f.parts());
        assertEquals(2, f.flows().getFirst().written());

        tell(Messages.PAUSE_TOPIC, new Pause(at + 200 * MS));
        assertEquals(1, took(Flows.class).getLast().flows().getFirst().written(), "stopped before the second, as of the cursor");
    }

    @Test
    void aRecordingSaysItDeclaresNoGraphAndShowsTheTopicsItsRowsCounted() {
        build("", "C:/runs/run.csv", "");
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        tell(Messages.ROWS_TOPIC, new Rows("replay:run.csv", 0, 0, List.of(new Row(1, 100 * MS, 0L, 3L,
                "vexel-logic", "bus", "send", "dev.example.Shown from dev.example.Bench 1", ""))));
        Flows f = took(Flows.class).getLast();
        assertEquals("a recording declares no bus graph: the topics its rows counted", f.why());
        assertEquals(List.of("dev.example.Shown"), f.flows().stream().map(Flow::topic).toList());
    }

    @Test
    void aRecordingNamedIsPlayedWhenTheFirstWindowOpensAndIsKeptAsAChosenRun() {
        build("", "C:/runs/run.csv", "");
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        assertEquals(List.of(new Replay("replay:run.csv", "C:/runs/run.csv")), took(Replay.class));
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        assertEquals(List.of(), took(Replay.class), "sent once");
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        assertTrue(sent.stream().noneMatch(Attach.class::isInstance), "a recording is not left for a running app");
        tell(Messages.ROWS_TOPIC, new Rows("replay:run.csv", 0, 0, List.of(pass(1, 100 * MS, "4ms"))));
        RunPanel p = took(RunPanel.class).getLast();
        assertEquals(new RunPanel("replay:run.csv", "run.csv", 0, Bench.REPLAYED,
                "no logic tick off the main thread: --logic=main, or idle", List.of(ONE), true), p);
        tell(Messages.DETACHED_TOPIC, new Detached("replay:run.csv", "the recording ended"));
        Timeline t = timeline();
        assertEquals("run.csv (replay:run.csv): the recording ended", t.status());
        assertEquals(1, passes(t).size(), "its tape stays to step through");
    }

    @Test
    void aCommandNamedIsStartedAndItsRunChosenOnceItCanBeInspected() {
        build("", "", "mvn.cmd exec:exec");
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        assertEquals(List.of(new LaunchChild("mvn.cmd exec:exec")), took(LaunchChild.class));
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        assertTrue(sent.stream().noneMatch(Attach.class::isInstance), "not the newest: the one it launched");
        assertEquals("Launching mvn.cmd exec:exec", timeline().status());
        tell(Messages.CHOOSE_TOPIC, new Choose(TWO, Bench.LAUNCHED));
        assertEquals(List.of(new Attach(TWO.name(), TWO.stream())), took(Attach.class));
        tell(Messages.ROWS_TOPIC, new Rows(TWO.name(), 0, 0, List.of(pass(1, 100 * MS, "4ms"))));
        assertEquals(Bench.LAUNCHED, took(RunPanel.class).getLast().from());
    }

    @Test
    void aCommandThatEndsBeforeItCanBeInspectedSaysWhy() {
        build("", "", "mvn.cmd exec:exec");
        tell(WindowOpened.TOPIC, new WindowOpened("main"));
        tell(Messages.CHILD_EXITED_TOPIC, new ChildExited("mvn.cmd exec:exec", "it exited with 1: BUILD FAILURE"));
        assertEquals("mvn.cmd exec:exec: it exited with 1: BUILD FAILURE", timeline().status());
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        assertTrue(sent.stream().noneMatch(Attach.class::isInstance), "it was chosen: nothing else is attached");
    }

    @Test
    void theRunPanelSaysTheRunHowItWasReachedAndTheChoices() {
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE, TWO), ""));
        RunPanel p = took(RunPanel.class).getLast();
        assertEquals(new RunPanel(TWO.name(), "Scheduler", 12, Bench.ATTACHED, "", List.of(ONE, TWO), false), p,
                "found, not chosen; nothing yet of its logic");
        tell(Messages.ROWS_TOPIC, new Rows(TWO.name(), 0, 0, List.of(
                row(1, 100 * MS, "vexel-logic", "bus", "tick", "0.3ms"))));
        assertEquals("logic on its own lane, vexel-logic", took(RunPanel.class).getLast().logic());
        tell(Messages.ROWS_TOPIC, new Rows(TWO.name(), 0, 0, List.of(pass(2, 110 * MS, "1ms"))));
        tell(Messages.CHOOSE_TOPIC, new Choose(ONE, Bench.LAUNCHED));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 200 * MS, "1ms"))));
        p = took(RunPanel.class).getLast();
        assertEquals(Bench.LAUNCHED, p.from());
        assertTrue(p.chosen());
        assertEquals("no logic tick off the main thread: --logic=main, or idle", p.logic());
    }

    @Test
    void aPassIsTheFrameZoneFromItsStartToItsEnd() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(
                pass(1, 100 * MS, "4ms"),
                new Row(2, 101 * MS, "main", "frame", "loop.park", "7ms"),
                pass(3, 120 * MS, "539.5us"))));
        Timeline t = timeline();
        assertEquals(List.of(new Mark(96 * MS, 100 * MS, Model.PASS, "main"),
                new Mark(120 * MS - 539_500, 120 * MS, Model.PASS, "main")), passes(t));
        assertEquals(120 * MS, t.edgeNanos(), "following: the newest row at the right edge");
        assertTrue(t.live());
        assertEquals("Inspecting Calculator (calculator-11)", t.status());
    }

    @Test
    void passesOlderThanTheMinuteKeptAreLetGo() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 1_000 * MS, "1ms"))));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0,
                List.of(pass(2, 1_000 * MS + Bench.REACH + MS, "1ms"))));
        assertEquals(2, passes(timeline()).size(), "past the panels' reach, but kept for the lane to glide over");
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0,
                List.of(pass(3, 1_000 * MS + Bench.RETAIN + MS, "1ms"))));
        assertEquals(List.of(new Mark(1_000 * MS + Bench.REACH, 1_000 * MS + Bench.REACH + MS, Model.PASS, "main"),
                        new Mark(1_000 * MS + Bench.RETAIN, 1_000 * MS + Bench.RETAIN + MS, Model.PASS, "main")),
                passes(timeline()));
    }

    @Test
    void lostRowsAreSaid() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 4, 9, List.of(pass(10, 50 * MS, "1ms"))));
        assertEquals("Lost rows 4..9 of Calculator (calculator-11)", timeline().status());
    }

    @Test
    void aRunThatWentAwayIsSaidAndAnotherIsAttachedOnTheNextBeat() {
        attached(ONE);
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "it went away"));
        Timeline t = timeline();
        assertEquals("Calculator (calculator-11): it went away", t.status());
        assertEquals("", t.run());
        assertFalse(t.live());
        tell(Messages.RUNS_TOPIC, new Runs(List.of(), ""));
        assertEquals("Calculator (calculator-11): it went away. " + Bench.NONE, timeline().status(),
                "the next beat finds nothing, and the reason stays beside it");
        tell(Messages.RUNS_TOPIC, new Runs(List.of(TWO), ""));
        assertEquals(List.of(new Attach("scheduler-12", "uds:two")), took(Attach.class));
    }

    @Test
    void aLateBatchOfARunSinceGoneChangesNothing() {
        attached(ONE);
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "stopped"));
        attached(TWO);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 5 * MS, "1ms"))));
        assertEquals(List.of(), took(Timeline.class));
    }

    // ------------------------------------------------------------------ the cursor

    /** A pass a millisecond long ending at each of {@code ends}, numbered from {@code seq}, in one batch. */
    private void passes(long seq, long... ends) {
        List<Row> rows = new ArrayList<>();
        for (long end : ends) {
            rows.add(pass(seq++, end, "1ms"));
        }
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, rows));
    }

    private static Mark span(long end) {
        return new Mark(end - MS, end, Model.PASS, "main");
    }

    @Test
    void spacePausesWhereTheLaneComesToRestAndRowsGoOnBeingKept() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        sent.clear();
        tell(Messages.PAUSE_TOPIC, new Pause(680 * MS));
        Timeline t = timeline();
        assertFalse(t.live(), "paused: nothing follows the present, so nothing is drawn");
        assertEquals(680 * MS, t.edgeNanos(), "where the view's lane comes to rest, a little past the newest row");
        assertEquals("Paused at 0.58 s of Calculator (calculator-11)", t.status(), "counted from the run's first row");
        passes(3, 900 * MS, 1_400 * MS);
        assertEquals(List.of(), took(Timeline.class), "paused: rows past the cursor change nothing shown");
        passes(5, 650 * MS);
        assertEquals(List.of(span(100 * MS), span(600 * MS), span(650 * MS)), passes(timeline()),
                "a late row before the cursor is shown; those after the pause are not on its island");
        tell(Messages.PAUSE_TOPIC, new Pause(0L));
        t = timeline();
        assertTrue(t.live(), "Space again follows the present");
        assertEquals(1_400 * MS, t.edgeNanos(), "and the rows kept meanwhile are there");
        assertEquals("Inspecting Calculator (calculator-11)", t.status());
    }

    @Test
    void steppingMovesTheCursorAndTheLaneKeepsEveryPassToGlideOver() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        passes(3, 1_300 * MS, 2_200 * MS);
        passes(5, 2_900 * MS);
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(-1_000 * MS, 2_950 * MS));
        Timeline t = timeline();
        assertEquals(1_950 * MS, t.edgeNanos(), "following, a step is from where the view's lane would rest");
        assertEquals(List.of(span(100 * MS), span(600 * MS), span(1_300 * MS), span(2_200 * MS), span(2_900 * MS)),
                passes(t), "every pass kept: those past the cursor are past the lane's right edge at rest");
        assertFalse(t.live());
        tell(Messages.STEP_TOPIC, new Step(-100 * MS, 0L));
        assertEquals(1_850 * MS, timeline().edgeNanos(), "paused, a step is from the cursor");
    }

    @Test
    void theCursorGoesNoFurtherThanTheTapeHolds() {
        attached(ONE);
        passes(1, 100 * MS, 1_500 * MS, 2_600 * MS);
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(-60_000 * MS, 2_600 * MS));
        Timeline t = timeline();
        assertEquals(100 * MS, t.edgeNanos(), "the oldest kept moment");
        assertEquals("Paused at 0.00 s of Calculator (calculator-11), the oldest moment kept", t.status());
        tell(Messages.STEP_TOPIC, new Step(60_000 * MS, 0L));
        t = timeline();
        assertEquals(2_600 * MS, t.edgeNanos(), "the newest row, and still paused");
        assertFalse(t.live());
    }

    /** Five minutes of passes after {@code fromNanos}, ten a second, a batch a second: far past what the tape keeps. */
    private void fiveMinutesAfter(long fromNanos) {
        long seq = 1_000;
        for (int second = 1; second <= 300; second++) {
            long[] ends = new long[10];
            for (int i = 0; i < ends.length; i++) {
                ends[i] = fromNanos + second * 1_000 * MS + i * 100 * MS;
            }
            passes(seq, ends);
            seq += ends.length;
        }
    }

    @Test
    void aStoppedCursorKeepsWhatItShowsWhileTheLiveTapeMovesOn() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        tell(Messages.PAUSE_TOPIC, new Pause(680 * MS));
        tell(Messages.STEP_TOPIC, new Step(-100 * MS, 0L));
        Readouts readBefore = readouts();
        Timeline before = timeline();
        fiveMinutesAfter(680 * MS);
        assertEquals(List.of(), took(Timeline.class), "rows after the cursor change nothing shown");
        tell(Messages.STEP_TOPIC, new Step(0L, 0L));
        assertEquals(readBefore, readouts(), "the past is recomputed as it was");
        Timeline after = timeline();
        assertEquals(before.edgeNanos(), after.edgeNanos());
        assertEquals(before.lanes(), after.lanes(), "five minutes later, the lanes are as they were, nothing let go");
        assertEquals(List.of(span(100 * MS), span(600 * MS)), passes(after), "and nothing of the present on them");
    }

    @Test
    void theCursorCannotLeaveItsIsland() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        tell(Messages.PAUSE_TOPIC, new Pause(680 * MS));
        fiveMinutesAfter(680 * MS);
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(60_000 * MS, 0L));
        Timeline t = timeline();
        assertEquals(680 * MS, t.edgeNanos(), "forward no further than where it stopped");
        assertFalse(t.live(), "still paused: only End follows the present");
        assertEquals("Paused at 0.58 s of Calculator (calculator-11)" + Bench.AT_THE_END, t.status());
        tell(Messages.SEEK_TOPIC, new Messages.Seek(200_000 * MS));
        assertEquals(680 * MS, timeline().edgeNanos(), "a click past the end lands on it");
        tell(Messages.STEP_TOPIC, new Step(-600_000 * MS, 0L));
        t = timeline();
        assertEquals(100 * MS, t.edgeNanos(), "back no further than the island holds, though the live tape is later");
        assertEquals("Paused at 0.00 s of Calculator (calculator-11), the oldest moment kept", t.status());
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, 5, 0L));
        assertEquals(on(span(600 * MS)), timeline().on(), "an arrow forward stops at the island's newest pass");
    }

    @Test
    void followingAgainLeavesTheIslandForThePresent() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        tell(Messages.PAUSE_TOPIC, new Pause(680 * MS));
        fiveMinutesAfter(680 * MS);
        sent.clear();
        tell(Messages.FOLLOW_TOPIC, new Follow());
        Timeline t = timeline();
        assertTrue(t.live());
        long newest = 680 * MS + 300_900 * MS;
        assertEquals(newest, t.edgeNanos(), "the present, recorded all the while");
        assertEquals(span(newest), passes(t).getLast());
        assertTrue(passes(t).getFirst().toNanos() >= newest - Bench.RETAIN, "and only the live minute");
        tell(Messages.FOLLOW_TOPIC, new Follow());
        assertEquals(List.of(), took(Timeline.class), "following already: End does nothing");
        tell(Messages.STEP_TOPIC, new Step(-1_000 * MS, newest));
        assertEquals(newest - 1_000 * MS, timeline().edgeNanos(), "and stopping again freezes a new island");
    }

    @Test
    void withNothingReadTheKeysDoNothing() {
        attached(ONE);
        tell(Messages.PAUSE_TOPIC, new Pause(0L));
        tell(Messages.STEP_TOPIC, new Step(-1_000 * MS, 0L));
        assertEquals(List.of(), took(Timeline.class));
    }

    @Test
    void aRunThatWentAwayCanStillBeSteppedThrough() {
        attached(ONE);
        passes(1, 100 * MS, 1_200 * MS);
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "it went away"));
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(-1_000 * MS, 1_200 * MS));
        Timeline t = timeline();
        assertEquals(200 * MS, t.edgeNanos());
        assertEquals("Calculator (calculator-11): it went away. Paused at 0.10 s", t.status());
    }

    @Test
    void theModelAsOfTheCursorHasOnlyTheRowsBeforeIt() {
        Tape tape = Tape.EMPTY;
        Model now = Model.EMPTY;
        for (List<Row> batch : List.of(List.of(pass(1, 100 * MS, "1ms"), pass(2, 600 * MS, "1ms")),
                List.of(pass(3, 1_300 * MS, "1ms"), pass(4, 2_200 * MS, "1ms")))) {
            now = now.fold(batch, Long.MAX_VALUE);
            tape = tape.add(batch, now.newestNanos(), Bench.RETAIN);
        }
        assertEquals(List.of(span(100 * MS), span(600 * MS), span(1_300 * MS)),
                tape.asOf(1_900 * MS).lane(Model.MAIN).marks().stream().map(BenchTest::bare).toList());
    }

    // ------------------------------------------------------------------ stepping by events

    @Test
    void anArrowStepsOntoEachPassInTurnAndSaysWhichItIsOn() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS, 1_300 * MS);
        passes(4, 2_200 * MS, 2_900 * MS);
        sent.clear();
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 2_950 * MS));
        Timeline t = timeline();
        assertEquals(on(span(2_900 * MS)), t.on(), "from following, back onto the newest pass");
        assertEquals(2_900 * MS, t.edgeNanos(), "the cursor at its end");
        assertFalse(t.live());
        assertEquals("Pass of 1.00 ms, ending at 2.800 s of Calculator (calculator-11)", t.status());
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 0L));
        assertEquals(on(span(2_200 * MS)), timeline().on(), "then the one before it, however far back");
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -2, 0L));
        assertEquals(on(span(600 * MS)), timeline().on(), "two at once");
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, 1, 0L));
        assertEquals(on(span(1_300 * MS)), timeline().on(), "and forward again");
    }

    @Test
    void atEitherEndAnArrowLeavesTheCursorWhereItIs() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 700 * MS));
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 0L));
        assertEquals(on(span(100 * MS)), timeline().on());
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 0L));
        assertEquals(List.of(), took(Timeline.class), "nothing before the oldest pass kept");
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, 5, 0L));
        assertEquals(on(span(600 * MS)), timeline().on(), "forward stops at the newest");
    }

    @Test
    void steppingByTimeOrPausingLeavesThePassItWasOn() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 700 * MS));
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(-100 * MS, 0L));
        Timeline t = timeline();
        assertEquals(Optional.empty(), t.on());
        assertEquals(500 * MS, t.edgeNanos());
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 0L));
        assertEquals(on(span(100 * MS)), timeline().on(), "back from between passes: the one before");
        tell(Messages.PAUSE_TOPIC, new Pause(0L));
        assertEquals(Optional.empty(), timeline().on(), "following again: on nothing");
    }

    @Test
    void aClickOnAPassPutsTheCursorOnItAndAnythingElseIsIgnored() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS, 1_300 * MS);
        sent.clear();
        tell(Messages.GO_TO_TOPIC, new GoTo(new Event(Model.MAIN, span(600 * MS))));
        Timeline t = timeline();
        assertEquals(on(span(600 * MS)), t.on(), "from following: paused on it");
        assertEquals(600 * MS, t.edgeNanos());
        tell(Messages.GO_TO_TOPIC, new GoTo(new Event(Model.MAIN, new Mark(1, 2, Model.PASS, "main"))));
        assertEquals(List.of(), took(Timeline.class), "not a pass that was kept");
    }

    @Test
    void aClickWithNoEventNearPutsTheCursorAtThatMomentWithinTheTape() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS, 1_300 * MS);
        sent.clear();
        tell(Messages.SEEK_TOPIC, new Messages.Seek(850 * MS));
        Timeline t = timeline();
        assertEquals(850 * MS, t.edgeNanos(), "from following: paused there");
        assertFalse(t.live());
        assertEquals(Optional.empty(), t.on(), "on no event");
        assertEquals("Paused at 0.75 s of Calculator (calculator-11)", t.status());
        tell(Messages.SEEK_TOPIC, new Messages.Seek(9_000 * MS));
        assertEquals(1_300 * MS, timeline().edgeNanos(), "no further forward than the newest row");
        tell(Messages.SEEK_TOPIC, new Messages.Seek(0L));
        assertEquals(100 * MS, timeline().edgeNanos(), "no further back than the tape holds: its first row");
    }

    @Test
    void theTapeKeepsTheLastMinuteAndLetsTheRestGo() {
        Tape tape = Tape.EMPTY;
        Model now = Model.EMPTY;
        for (long second = 0; second < 90; second++) {
            List<Row> batch = List.of(pass(second, 1_000 * MS * second + 500 * MS, "1ms"));
            now = now.fold(batch, Long.MAX_VALUE);
            tape = tape.add(batch, now.newestNanos(), Bench.RETAIN);
        }
        assertEquals(61, tape.seconds().size(), "the second holding the cut, and the sixty after it");
        assertEquals(29_000 * MS + 500 * MS, tape.oldestNanos());
    }

    @Test
    void theModelAsOfAMomentIsExactlyTheModelHeldLiveThen() {
        // Batches of uneven size and timing, crossing many seconds, with rows of other kinds among the passes:
        // whatever the bench held live after each, the tape gives back for that moment.
        Tape tape = Tape.EMPTY;
        Model now = Model.EMPTY;
        List<Model> held = new ArrayList<>();
        long t = 5_000 * MS;
        long seq = 1;
        for (int b = 0; b < 200; b++) {
            List<Row> batch = new ArrayList<>();
            for (int i = 0; i < 1 + b % 7; i++) {
                t += (3 + (b * 31 + i * 17) % 290) * MS;
                batch.add(i % 3 == 2 ? new Row(seq++, t, "main", "frame", "loop.park", "2ms")
                        : pass(seq++, t, (1 + i) + "ms"));
            }
            now = now.fold(batch, Long.MAX_VALUE);
            tape = tape.add(batch, now.newestNanos(), Long.MAX_VALUE);
            held.add(now);
        }
        assertTrue(tape.seconds().size() > 50, "the run crossed many checkpoints");
        for (Model m : held) {
            assertEquals(m, tape.asOf(m.newestNanos()));
        }
    }

    // ------------------------------------------------------------------ the lanes and the readouts

    /** One draw as a traced run writes it (seen 2026-10-30): the GPU's waits, the present, the pass, then a hold. */
    private static List<Row> aDraw(long seq, long at) {
        return List.of(
                row(seq, at - 2_900_000, "main", "gpu", "dxgi wait", "2.9us"),
                row(seq + 1, at - 2_800_000, "main", "gpu", "wait fence", "15.4us"),
                row(seq + 2, at - 1_000_000, "main", "draw", "emit canvas", "677.4us"),
                row(seq + 3, at, "main", "gpu", "present frame", "1.95ms"),
                row(seq + 4, at + 10_000, "main", "frame", "frame", "2.92ms"),
                row(seq + 5, at + 20_000, "main", "frame", "frame.present", "#5"),
                row(seq + 6, at + 13 * MS, "main", "frame", "draw held for the ceiling", "1"));
    }

    @Test
    void eachLaneIsReadFromItsOwnRows() {
        attached(ONE);
        List<Row> rows = new ArrayList<>(aDraw(1, 100 * MS));
        rows.add(row(10, 90 * MS, "main", "input", "edges", "0"));
        rows.add(row(11, 91 * MS, "main", "input", "edges", "2"));
        rows.add(row(12, 92 * MS, "main", "input", "snapshot", "4.0us"));
        rows.add(row(13, 93 * MS, "vexelray-automation-1", "input", "key", "SPACE"));
        rows.add(row(17, 94 * MS, "vexelray-automation-1", "input", "pointer.press", "LEFT"));
        rows.add(row(14, 95 * MS, "vexel-logic", "bus", "tick", "448.1us"));
        rows.add(row(15, 96 * MS, "main", "bus", "tick", "1.26ms"));
        rows.add(row(16, 120 * MS, "main", "frame", "draw held for the GPU", "1"));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, rows));
        Timeline t = timeline();
        assertEquals(Model.LANES, t.lanes().stream().map(Messages.Lane::name).toList(), "every lane, in order");
        assertEquals(List.of(new Mark(91 * MS, 91 * MS, Model.INPUT_TAKEN, "main"),
                new Mark(93 * MS, 93 * MS, Model.INJECTED, "vexelray-automation-1"),
                new Mark(94 * MS, 94 * MS, Model.INJECTED, "vexelray-automation-1")),
                lane(t, Model.INPUT), "a snapshot that took nothing is no input; an automation verb, a click's too, is told apart,"
                        + " and each says what it was");
        assertEquals(List.of(new Mark(100 * MS + 10_000 - 2_920_000, 100 * MS + 10_000, Model.PASS, "main")), passes(t));
        assertEquals(List.of(new Mark(95 * MS - 448_100, 95 * MS, Model.TICK, "vexel-logic")), lane(t, Model.LOGIC),
                "the logic lane's thread only: main's tick is the widgets'");
        assertEquals(List.of(new Mark(100 * MS - 1_950_000, 100 * MS, Model.DRAW, "main"),
                        new Mark(113 * MS, 113 * MS, Model.HELD_FOR_CEILING, "main"),
                        new Mark(120 * MS, 120 * MS, Model.HELD_FOR_GPU, "main")),
                lane(t, Model.DRAWING));
        assertEquals(List.of(new Mark(100 * MS - 2_900_000 - 2_900, 100 * MS - 2_900_000, "dxgi wait", "main"),
                        new Mark(100 * MS - 2_800_000 - 15_400, 100 * MS - 2_800_000, "wait fence", "main")),
                lane(t, Model.GPU), "what the presenter waited on; recording is not a wait");
    }

    @Test
    void aMessageIsAMomentOnItsOwnLaneAndTheDetailsSayWhatTheRunsClockSaid() {
        attached(ONE);
        long wall = 1_793_000_000_000_000_000L;      // what the run's clock said at 100 ms on this machine's
        String rows = "dev.sibarum.inspector.core.Messages.Rows";
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(
                new Row(1, 100 * MS, wall, "main", "frame", "frame", "1ms"),
                new Row(2, 110 * MS, wall + 10 * MS, "vexel-source-link-rows", "bus", rows, "21.8us"),
                new Row(3, 111 * MS, wall + 11 * MS, "main", "bus", "vexelray.gui.main.mutations depth", "1"),
                new Row(4, 112 * MS, wall + 12 * MS, "main", "bus", "vexelray.gui.main.node.resize", "5.0us"),
                new Row(5, 113 * MS, wall + 13 * MS, "main", "bus", "pump drain", "25.1us"),
                new Row(6, 114 * MS, wall + 14 * MS, "vexel-job-1", "bus", "uncaused vexel-job-1", "1"))));
        Timeline t = timeline();
        Mark published = new Mark(110 * MS, 110 * MS, Model.MESSAGE, "vexel-source-link-rows");
        assertEquals(List.of(published), lane(t, Model.MESSAGES), "the bus's own rows are no message");
        sent.clear();
        tell(Messages.GO_TO_TOPIC, new GoTo(new Event(Model.MESSAGES, published)));
        assertEquals(new Details(ONE.name(), 110 * MS, 10 * MS, wall + 10 * MS,
                Optional.of(new Event(Model.MESSAGES, published))), details());
        assertEquals("Message Messages.Rows at 0.010 s of Calculator (calculator-11)", timeline().status());
        assertEquals("Messages.Rows", Model.typeName(rows));
        assertEquals("dev.sibarum.inspector.core", Model.typePackage(rows));
        assertEquals("WindowOpened", Model.typeName("dev.vexelray.framework.core.WindowOpened"));
    }

    @Test
    void untilARowSaysItsWallClockTheDetailsSayItIsNotKnown() {
        attached(ONE);
        passes(1, 100 * MS, 600 * MS);
        Details d = details();
        assertEquals(600 * MS, d.atNanos(), "following: the newest row");
        assertEquals(-1L, d.clockNanos());
        assertEquals(Optional.empty(), d.on());
    }

    @Test
    void anArrowStepsThroughTheFocusedLaneOnlyAndSaysWhatItIsOn() {
        attached(ONE);
        List<Row> rows = new ArrayList<>(aDraw(1, 100 * MS));
        rows.addAll(aDraw(20, 200 * MS));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, rows));
        sent.clear();
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.DRAWING, -1, 250 * MS));
        Timeline t = timeline();
        assertEquals(Optional.of(new Event(Model.DRAWING, new Mark(213 * MS, 213 * MS, Model.HELD_FOR_CEILING, "main"))),
                t.on(), "the drawing lane's newest event, a hold, not a pass that ended later");
        assertEquals("Draw held for the ceiling at 0.116 s of Calculator (calculator-11)", t.status(),
                "a moment says when; counted from the run's first row, the first GPU wait");
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.DRAWING, -1, 0L));
        t = timeline();
        assertEquals(new Mark(200 * MS - 1_950_000, 200 * MS, Model.DRAW, "main"), t.on().orElseThrow().mark());
        assertTrue(t.status().startsWith("Draw of 1.95 ms, ending at"), t.status());
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent(Model.MAIN, -1, 0L));
        assertEquals(Model.MAIN, timeline().on().orElseThrow().lane(),
                "another lane's arrow starts from the cursor, onto that lane's event before it");
        tell(Messages.STEP_EVENT_TOPIC, new StepEvent("Nowhere", -1, 0L));
        assertEquals(List.of(), took(Timeline.class), "a lane that is not one is ignored");
    }

    @Test
    void theReadoutsCountTheSecondBeforeTheCursorAndRewindWithIt() {
        attached(ONE);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.addAll(aDraw(10L * i, 100 * MS * (i + 1)));
            rows.add(row(10L * i + 8, 100 * MS * (i + 1) + 5 * MS, "vexel-logic", "bus", "tick", (i + 1) + "00us"));
        }
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, rows));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(
                row(-1, 2_050 * MS, "", Model.LOST_LANE, Model.LOST, "6"),
                pass(300, 2_100 * MS, "1ms"))));
        Readouts live = readouts();
        assertEquals(new Readouts(ONE.name(), 9, 10, 0, 10, 1_500_000, 2_000_000, 6), live,
                "after 1.1 s: nine draws (the one ending at 1.1 s is out), ten holds and ticks, and the lost rows");
        sent.clear();
        tell(Messages.STEP_TOPIC, new Step(-1_000 * MS, 2_100 * MS));
        Readouts then = readouts();
        assertEquals(new Readouts(ONE.name(), 10, 10, 0, 10, 500_000, 1_000_000, 0), then,
                "a second back: the ticks as they were then, and nothing lost yet");
    }

    @Test
    void lostRowsAreSaidAsARowOfTheirOwnAtTheBatchsFirstMoment() {
        Rows r = LinkRows.rows("run", 1_000L, new dev.vexelray.gui.inspector.link.RowBatch(4, 9, List.of(
                new dev.vexelray.gui.inspector.link.Row(10, 50L, 0L, 0L, "main", "frame", "frame", "1ms", ""))));
        assertEquals(row(-1, 1_050L, "", Model.LOST_LANE, Model.LOST, "6"), r.rows().getFirst());
        assertEquals(2, r.rows().size());
    }

    @Test
    void aRowKeepsItsPassAndCauseAndTheLinksSourcesComeFirst() {
        Rows r = LinkRows.rows("run", 1_000L, new dev.vexelray.gui.inspector.link.RowBatch(0, -1, List.of(
                new dev.vexelray.gui.inspector.link.Row(10, 50L, 7L, 64L, "main", "frame", "frame", "1ms",
                        "76673.t03.40"),
                new dev.vexelray.gui.inspector.link.Row(11, 51L, 8L, 64L, "main", "frame", "wake", "x", null))),
                List.of("t03 gui.frame"));
        assertEquals(new Row(-1, 1_050L, "", Model.APP_LANE, Model.SOURCE, "t03 gui.frame"), r.rows().getFirst(),
                "the source table, as the rows that named it, at the batch's first moment");
        assertEquals(new Row(10, 1_050L, 7L, 64L, "main", "frame", "frame", "1ms", "76673.t03.40"), r.rows().get(1));
        assertEquals("", r.rows().get(2).cause(), "a writer older than causes sent none");
    }

    @Test
    void aPassIsSetOffByWhatWokeItAndSaysWhetherItDrewAndHowLongItParked() {
        long at = 2_000 * MS;
        List<Row> rows = List.of(
                new Row(1, at, 0L, 0L, "main", Model.APP_LANE, Model.SOURCE, "o00 source dev.example.Rows", ""),
                new Row(2, at, 0L, 0L, "main", Model.APP_LANE, Model.SOURCE, "t03 gui.frame", ""),
                new Row(3, at + MS, 0L, 63L, "vexel-logic", "frame", "wake", "mutation on Gui@1d08", "76673.o00.6c"),
                new Row(4, at + 2 * MS, 0L, 64L, "main", "frame", "draw skipped", "1", "76673.t03.40"),
                new Row(5, at + 3 * MS, 0L, 64L, "main", "frame", "frame", "1.61ms", "76673.t03.40"),
                new Row(6, at + 3 * MS, 0L, 64L, "main", "frame", "frame.update", "#64", "76673.t03.40"),
                new Row(7, at + 4 * MS, 0L, 64L, "main", "frame", "loop.park", "200ms", "76673.t03.40"),
                new Row(8, at + 5 * MS, 0L, 64L, "main", "frame", "wait for events", "200.29ms", "76673.t03.40"));
        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        Mark pass = m.lane(Model.MAIN).marks().getFirst();
        assertEquals(List.of(
                new Field(Model.TYPE, "pass"),
                new Field(Model.PASS_NUMBER, "#64"),
                new Field(Model.TOOK, "1.61 ms"),
                new Field(Model.THREAD, "main"),
                new Field(Model.DREW, Model.DREW_NOTHING),
                new Field(Model.PARKED, "200ms"),
                new Field(Model.WAITED, "200.29ms")), pass.fields(),
                "why it did not draw outranks that it only updated");
        assertEquals("76673.o00.6c", pass.cause(), "every pass is the loop's timer: what made this one run woke it");
        assertEquals("I/O dev.example.Rows, firing 108   (76673.o00.6c) → woke pass #64", m.pathOf(pass, rows));
        assertTrue(m.ahead().isEmpty(), "nothing left waiting for a pass");
    }

    @Test
    void anEventsPathIsEveryHopItsCauseTookFoldedByWhereItHappened() {
        long at = 2_000 * MS;
        String cause = "b3702.o00.28";
        String rowsType = "dev.sibarum.inspector.core.Messages.Rows";
        String timeline = "dev.sibarum.inspector.core.Messages.Timeline";
        List<Row> rows = new ArrayList<>(List.of(
                new Row(1, at, 0L, 0L, "main", Model.APP_LANE, Model.SOURCE, "o00 source dev.example.LinkRows", ""),
                new Row(2, at, 0L, 9L, "vexel-source-link-rows", "bus", rowsType, "9.4us", cause),
                new Row(3, at + 100_000, 0L, 9L, "vexel-logic", "bus", "receive",
                        rowsType + " to dev.sibarum.inspector.core.Bench 0", cause),
                new Row(4, at + 130_000, 0L, 9L, "vexel-logic", "state", "ledger bench", "24.2us", cause),
                new Row(5, at + 120_000, 0L, 9L, "vexel-logic", "bus", "send",
                        timeline + " from dev.sibarum.inspector.core.Bench 1", cause),
                new Row(6, at + 200_000, 0L, 9L, "main", "bus", "receive",
                        timeline + " to widgets dev.sibarum.inspector.View#timeline 1", cause)));
        for (int i = 0; i < 37; i++) {
            rows.add(new Row(7 + i, at + 300_000 + i * 1_000L, 0L, 9L, "main", "layout",
                    i < 2 ? "mutation layout" : i == 2 ? "mutation draw" : "mutation unchanged", "SetText", cause));
        }
        rows.add(new Row(50, at + 900_000, 0L, 9L, "main", "frame", "wake", "mutation on Gui@7f13", cause));
        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        Mark published = m.lane(Model.MESSAGES).marks().getFirst();
        assertEquals("I/O dev.example.LinkRows, firing 40   (b3702.o00.28) → published Messages.Rows on"
                + " vexel-source-link-rows → reached Messages.Rows at Bench +0.11 ms"
                + " → ledger bench committed its state on vexel-logic +0.01 ms"
                + " → sent Messages.Timeline from Bench +0.01 ms"
                + " → reached Messages.Timeline at View#timeline in widgets +0.08 ms"
                + " → 37 changes to the GUI on main (2 relaid out, 1 redrawn) +0.10 ms → woke pass #10 +0.60 ms",
                m.pathOf(published, rows),
                "from the source to the pass it woke, a hop at a time, a named mailbox by its method, with the time"
                + " from each hop to the next");
        assertEquals(List.of("Messages.Rows", "Messages.Timeline"), m.lane(Model.MESSAGES).marks().stream()
                .map(e -> e.field(Model.TYPE)).toList(), "a component's send is a message too");
    }

    @Test
    void aClicksPathNamesTheTickThatHeardItAndTheDrawThatShowedWhatItChanged() {
        long at = 2_000 * MS;
        String click = "b3702.i01.5";
        String frame = "b3702.t03.90";
        String pressed = "dev.vexelray.gui.widget.Button.Pressed";
        List<Row> rows = List.of(
                new Row(1, at, 0L, 0L, "main", Model.APP_LANE, Model.SOURCE, "i01 automation", ""),
                new Row(2, at, 0L, 40L, "main", "bus", pressed, "3.0us", click),
                new Row(3, at + 100_000, 0L, 40L, "vexel-logic", "bus", "receive",
                        pressed + " to dev.example.Counter 0", click),
                new Row(4, at + 150_000, 0L, 40L, "vexel-logic", "bus", "send",
                        "dev.example.Counted from dev.example.Counter 1", click),
                new Row(5, at + 200_000, 0L, 40L, "vexel-logic", "bus", "tick", "120us", ""),
                new Row(6, at + 200_000, 0L, 40L, "vexel-logic", "bus", Model.TICK_RAN, "#7 2 rounds 2 delivered", ""),
                new Row(7, at + 300_000, 0L, 41L, "main", "layout", "mutation layout", "SetText", click),
                new Row(8, at + 900_000, 0L, 41L, "main", "draw", Model.DRAW_RAN, "#31 " + frame + " " + click + " +2",
                        frame),
                new Row(9, at + 1_000_000, 0L, 41L, "main", "gpu", "present frame", "300us", frame),
                new Row(10, at + 1_100_000, 0L, 41L, "main", "frame", "frame", "900us", frame),
                new Row(11, at + 1_100_000, 0L, 41L, "main", "frame", "frame.present", "#41", frame));
        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        Mark tick = m.lane(Model.LOGIC).marks().getFirst();
        assertEquals("#7", tick.field(Model.TICK_NUMBER), "the stack's number, from the row after the zone");
        assertEquals("2", tick.field(Model.ROUNDS));
        assertEquals("2", tick.field(Model.DELIVERED));
        assertEquals("", tick.field(Model.CARRIED), "said only when its deadline cut it short");
        Mark draw = m.lane(Model.DRAWING).marks().getFirst();
        assertEquals("#31", draw.field(Model.DRAW_NUMBER), "a draw's number, apart from its pass's");
        assertTrue(draw.field(Model.SHOWED).startsWith("timer "), draw.field(Model.SHOWED));
        assertTrue(draw.field(Model.SHOWED).endsWith("; and 2 more"), draw.field(Model.SHOWED));
        assertEquals("#31", m.lane(Model.MAIN).marks().getFirst().field(Model.DRAW_NUMBER), "and the pass that drew it");
        Mark clicked = m.lane(Model.MESSAGES).marks().getFirst();
        assertEquals("input automation, firing 5   (b3702.i01.5) → published Button.Pressed on main"
                + " → reached Button.Pressed at Counter in tick #7 +0.10 ms"
                + " → sent Counted from Counter in tick #7 +0.05 ms"
                + " → 1 change to the GUI on main (1 relaid out, 0 redrawn) +0.15 ms"
                + " → shown by draw #31 +0.60 ms", m.pathOf(clicked, rows),
                "input, the tick that heard it, the change it made and the draw that showed it, with the time between");
        Messages.Traced traced = Model.traced("run", clicked, Model.hopsOf(clicked.cause(), rows));
        assertEquals(click, traced.cause());
        assertEquals(List.of(40L, 41L), traced.passes(), "the pass it was published in, and the one that changed the GUI");
        assertEquals(List.of("#7"), traced.ticks());
        assertEquals(List.of("#31"), traced.draws());
        assertEquals(List.of(new Messages.Crossed(pressed, ""),
                new Messages.Crossed(pressed, "dev.example.Counter"),
                new Messages.Crossed("dev.example.Counted", "")), traced.crossed(),
                "each write and each delivery, a receiver as the flows name it");
        assertTrue(traced.has(Model.LOGIC, tick), "the tick by its number, though it runs in no cause of the click's");
        assertTrue(traced.has(Model.DRAWING, draw), "and the draw");
        assertTrue(traced.has(Model.MESSAGES, clicked));
        assertTrue(traced.has(Model.MAIN, m.lane(Model.MAIN).marks().getFirst().because("")),
                "pass #41 changed the GUI and drew it: on the path by its number, whatever woke it");
        assertFalse(Messages.Traced.NONE.has(Model.MESSAGES, clicked.because("")), "nothing is on no path");
    }

    @Test
    void everyEventKeepsTheSchemasFieldsInItsOrderAndNoneItCannotFill() {
        long at = 2_000 * MS;
        String rows = "dev.sibarum.inspector.core.Messages.Rows";
        Model m = Model.EMPTY.fold(List.of(
                new Row(1, at, 0L, 0L, "main", Model.APP_LANE, Model.SOURCE, "o00 source dev.example.Rows", ""),
                new Row(2, at, 0L, 7L, "vexel-source-link-rows", "bus", rows, "21.8us", "76673.o00.6c"),
                new Row(3, at + MS, 0L, 7L, "main", "frame", Model.HELD_FOR_GPU, "1", "")), Long.MAX_VALUE);
        assertEquals(List.of(new Field(Model.TYPE, "Messages.Rows"),
                new Field(Model.PASS_NUMBER, "#7"), new Field(Model.THREAD, "vexel-source-link-rows"),
                new Field(Model.PACKAGE, "dev.sibarum.inspector.core")),
                m.lane(Model.MESSAGES).marks().getFirst().fields(),
                "the path is filled when the details are said, since it grows after the event");
        Mark held = m.lane(Model.DRAWING).marks().getFirst();
        assertEquals("", m.pathOf(held, List.of()), "a row with no cause has no path");
        assertEquals("the GPU had not finished the last frame", held.field(Model.REASON));
        for (Lane l : m.lanes()) {
            for (Mark e : l.marks()) {
                List<Integer> order = e.fields().stream().map(f -> Model.FIELDS.indexOf(f.name())).toList();
                assertEquals(order.stream().sorted().toList(), order, "in the schema's order");
                assertTrue(e.fields().stream().noneMatch(f -> f.value().isEmpty()), "no empty field");
            }
        }
    }

    @Test
    void aLaneTheRunsProbeDoesNotRecordSaysSoAndARunThatNeverSaidIsTakenAtItsWord() {
        Rows r = LinkRows.rows("run", 1_000L, new dev.vexelray.gui.inspector.link.RowBatch(0, -1, List.of(
                new dev.vexelray.gui.inspector.link.Row(10, 50L, 0L, 1L, "main", "frame", "frame", "1ms", ""))),
                List.of(), List.of("input", "frame", "layout", "app"));
        assertEquals(new Row(-1, 1_050L, "", Model.APP_LANE, Model.RECORDED, "input frame layout app"),
                r.rows().getFirst(), "what automation alone records");
        Model m = Model.EMPTY.fold(r.rows(), Long.MAX_VALUE);
        assertEquals(List.of(Model.INPUT, Model.MAIN), m.lanes().stream().filter(Lane::recorded).map(Lane::name)
                .toList(), "ticks and messages are on the bus lane, draws and waits on the GPU's");
        assertTrue(Model.EMPTY.fold(List.of(), Long.MAX_VALUE).lanes().stream().allMatch(Lane::recorded),
                "a run that has not said what it records is not said to be missing anything");
    }

    @Test
    void aRunOfAnotherLinkVersionIsShownUnderAWarning() {
        int ours = dev.vexelray.gui.inspector.link.InspectorLink.VERSION;
        assertEquals("", LinkRows.versionWarning(Optional.of(ours)), "the same version sends everything read here");
        assertTrue(LinkRows.versionWarning(Optional.empty()).endsWith("rebuild the application against this stack."),
                "an application older than the version itself");
        assertTrue(LinkRows.versionWarning(Optional.of(ours - 1)).endsWith("rebuild the application against this stack."));
        assertTrue(LinkRows.versionWarning(Optional.of(ours + 1)).endsWith("upgrade this Inspector."));

        String warning = LinkRows.versionWarning(Optional.empty());
        Rows r = LinkRows.rows("run", 1_000L, new dev.vexelray.gui.inspector.link.RowBatch(0, -1, List.of(
                new dev.vexelray.gui.inspector.link.Row(10, 50L, 0L, 1L, "main", "frame", "frame", "1ms", ""))),
                List.of(), null, warning);
        assertEquals(new Row(-1, 1_050L, "", Model.APP_LANE, Model.WARNING, warning), r.rows().getFirst());
        Model m = Model.EMPTY.fold(r.rows(), Long.MAX_VALUE);
        assertEquals(warning, m.warning());
        assertEquals(warning, m.fold(List.of(), Long.MAX_VALUE).warning(), "kept for as long as the run is");
        assertEquals("", Model.EMPTY.warning());
        assertEquals("", m.fold(LinkRows.rows("run", 1_000L, new dev.vexelray.gui.inspector.link.RowBatch(0, -1,
                List.of()), List.of(), null, "").rows(), Long.MAX_VALUE).warning(), "a stream that opens clears it");
    }

    @Test
    void aLinkThatCannotBeReadIsWarnedOfAndStaysWarnedOfWhileAttachingAgain() {
        String unreadable = LinkRows.unreadable(new RuntimeException("its link failed",
                new sibarum.intrabuf.IntrabufException("SNAPSHOT_DRIFT: fingerprints differ")));
        assertTrue(unreadable.contains("different versions of the stack"));
        assertEquals("", LinkRows.unreadable(new RuntimeException("connection reset")), "only a schema it cannot read");

        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        tell(Messages.DETACHED_TOPIC, new Detached(ONE.name(), "its link failed: SNAPSHOT_DRIFT", unreadable));
        assertEquals(unreadable, timeline().warning());
        tell(Messages.RUNS_TOPIC, new Runs(List.of(ONE), ""));
        Timeline again = timeline();
        assertTrue(again.status().startsWith("Attaching to"));
        assertEquals(unreadable, again.warning(), "no flicker while it tries again");
    }

    @Test
    void aCauseIsSaidByWhatTheRunCalledIt() {
        Map<String, String> names = Map.of("c11", "automation.command", "c11.2", "key SPACE", "i04", "input.key");
        assertEquals("automation command \"key SPACE\"   (76673.c11.2)", Model.causeOf("76673.c11.2", names));
        assertEquals("input input.key, firing 5   (76673.i04.5)", Model.causeOf("76673.i04.5", names));
        assertEquals("timer not named by the run, firing 1   (76673.t09.1)", Model.causeOf("76673.t09.1", names));
        assertEquals("", Model.causeOf("", names));
        assertEquals("nonsense", Model.causeOf("nonsense", names), "said as it is");
    }

    @Test
    void anInjectedKeyAndADevicesEdgesSayWhatTheyWere() {
        long at = 2_000 * MS;
        List<Row> rows = List.of(
                new Row(1, at, 0L, 5L, "vexelray-automation-1", "input", "key", "SPACE", "76673.c11.2"),
                new Row(2, at + MS, 0L, 6L, "main", "input", "edges", "3", "76673.t03.6"));
        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        List<Mark> input = m.lane(Model.INPUT).marks();
        assertEquals("key", input.get(0).field(Model.VERB));
        assertEquals("SPACE", input.get(0).field(Model.SENT));
        assertEquals("#5", input.get(0).field(Model.PASS_NUMBER));
        assertEquals("automation command not named by the run, firing 2   (76673.c11.2) → automation key SPACE",
                m.pathOf(input.get(0), rows), "a command the run never said: its source's kind and firing");
        assertEquals("3", input.get(1).field(Model.EDGES));
        assertEquals("the devices → read in pass #6", m.pathOf(input.get(1), rows));
    }

    @Test
    void durationsAreReadInEachUnitTheProbeWrites() {
        assertEquals(51_680_000L, Durations.nanos("51.68ms"));
        assertEquals(539_300L, Durations.nanos("539.3us"));
        assertEquals(820L, Durations.nanos("820ns"));
        assertEquals(1_200_000_000L, Durations.nanos("1.2s"));
        assertEquals(-1L, Durations.nanos("#1"));
        assertEquals(-1L, Durations.nanos("widget message on Gui@1"));
    }

    @Test
    void aFoldLeavesTheModelItFoldedOntoAsItWas() {
        long at = 2_000 * MS;
        Model before = Model.EMPTY;
        for (int pass = 1; pass <= 200; pass++) {
            before = before.fold(List.of(new Row(pass, at + pass * MS, 0L, pass, "main", "frame", "frame", "0.5ms",
                    "")), Long.MAX_VALUE, Bench.RETAIN);
        }
        List<Mark> passes = List.copyOf(before.lane(Model.MAIN).marks());
        Model after = before.fold(List.of(
                new Row(201, at + 201 * MS, 0L, 200L, "main", "frame", "frame.present", "#200", "")),
                Long.MAX_VALUE, Bench.RETAIN);
        Model beside = before.fold(List.of(
                new Row(201, at + 201 * MS, 0L, 201L, "main", "frame", "frame", "0.5ms", "")),
                Long.MAX_VALUE, Bench.RETAIN);
        assertEquals(passes, before.lane(Model.MAIN).marks(), "neither fold changed what it folded onto");
        assertEquals(Model.DREW_YES, after.lane(Model.MAIN).marks().getLast().field(Model.DREW));
        assertEquals("", before.lane(Model.MAIN).marks().getLast().field(Model.DREW));
        assertEquals(201, beside.lane(Model.MAIN).marks().size());
        assertEquals(200, after.lane(Model.MAIN).marks().size(), "a fold beside another sees none of its events");
    }
}
