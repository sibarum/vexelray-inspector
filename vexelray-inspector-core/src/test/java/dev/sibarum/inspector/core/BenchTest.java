package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.sibarum.inspector.core.Messages.Span;
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
        bus = Atchung.create();
        tick = bus.tick(null);
        timer = new Timer(bus);
        new Bench(tick.inbox("bench"), timer);
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(Messages.FIND_RUNS_TOPIC, Messages.ATTACH_TOPIC, Messages.TIMELINE_TOPIC)) {
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
        return took(Timeline.class).getLast();
    }

    /** A pass of the run's main loop: the frame zone's row at its end, carrying how long it took. */
    private static Row pass(long seq, long endNanos, String took) {
        return new Row(seq, endNanos, "main", "frame", "frame", took);
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
    void aPassIsTheFrameZoneFromItsStartToItsEnd() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(
                pass(1, 100 * MS, "4ms"),
                new Row(2, 101 * MS, "main", "frame", "loop.park", "7ms"),
                pass(3, 120 * MS, "539.5us"))));
        Timeline t = timeline();
        assertEquals(List.of(new Span(96 * MS, 100 * MS), new Span(120 * MS - 539_500, 120 * MS)), t.passes());
        assertEquals(120 * MS, t.newestNanos());
        assertTrue(t.live());
        assertEquals("Inspecting Calculator (calculator-11)", t.status());
    }

    @Test
    void passesOlderThanTheReachAreLetGo() {
        attached(ONE);
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0, List.of(pass(1, 1_000 * MS, "1ms"))));
        tell(Messages.ROWS_TOPIC, new Rows(ONE.name(), 0, 0,
                List.of(pass(2, 1_000 * MS + Bench.REACH + MS, "1ms"))));
        assertEquals(List.of(new Span(1_000 * MS + Bench.REACH, 1_000 * MS + Bench.REACH + MS)),
                timeline().passes());
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

    @Test
    void durationsAreReadInEachUnitTheProbeWrites() {
        assertEquals(51_680_000L, Durations.nanos("51.68ms"));
        assertEquals(539_300L, Durations.nanos("539.3us"));
        assertEquals(820L, Durations.nanos("820ns"));
        assertEquals(1_200_000_000L, Durations.nanos("1.2s"));
        assertEquals(-1L, Durations.nanos("#1"));
        assertEquals(-1L, Durations.nanos("widget message on Gui@1"));
    }
}
