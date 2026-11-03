package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.AskReplay;
import dev.sibarum.inspector.core.Messages.Difference;
import dev.sibarum.inspector.core.Messages.ReplayReport;
import dev.sibarum.inspector.core.Messages.Replayed;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.RunAgain;
import dev.sibarum.inspector.core.Messages.RunPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Running the attached run again, on a tick of this test's: what is asked, of whom, and what the answer says. */
class RerunTest {

    private static final Run W2 = new Run("w2-1", "Vexel witness components", 42, "tcp:5555");

    private Atchung bus;
    private Tick tick;
    private Rerun rerun;
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        bus = Atchung.create();
        tick = bus.tick(null);
        rerun = new Rerun(tick.inbox("rerun"));
        Inbox heard = tick.inbox("heard");
        heard.subscribe(Messages.ASK_REPLAY_TOPIC, sent::add, 64);
        heard.subscribe(Messages.REPLAY_REPORT_TOPIC, sent::add, 64);
    }

    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    private <T> List<T> took(Class<T> kind) {
        return sent.stream().filter(kind::isInstance).map(kind::cast).toList();
    }

    private static RunPanel attachedTo(String run) {
        return new RunPanel(run, run.equals(W2.name()) ? W2.title() : "", 42, "launched", "", List.of(W2), true);
    }

    @Test
    void theButtonAsksTheAttachedRunsLinkAndItsAnswerIsTheReport() {
        tell(Messages.RUN_PANEL_TOPIC, attachedTo(W2.name()));
        tell(Messages.RUN_AGAIN_TOPIC, new RunAgain());
        assertEquals(List.of(new AskReplay("w2-1", "tcp:5555")), took(AskReplay.class));
        assertTrue(rerun.state().report().asking());
        assertEquals("Running Vexel witness components again", rerun.state().report().said());
        tell(Messages.RUN_AGAIN_TOPIC, new RunAgain());
        assertEquals(1, took(AskReplay.class).size(), "pressed again while asking: nothing more");

        tell(Messages.REPLAYED_TOPIC, new Replayed("w2-1", "ok replay ticks=6 sends=6 differ=0"));
        assertFalse(rerun.state().report().asking());
        assertEquals("Ran 6 ticks of its logic again: all 6 sends the same", rerun.state().report().said());
        assertEquals(rerun.state().report(), took(ReplayReport.class).getLast());

        tell(Messages.REPLAYED_TOPIC, new Replayed("other-2", "ok replay ticks=1 sends=1 differ=1"));
        assertEquals("Ran 6 ticks of its logic again: all 6 sends the same", rerun.state().report().said(),
                "another run's answer is not this one's");
        tell(Messages.RUN_PANEL_TOPIC, attachedTo(""));
        assertEquals(ReplayReport.NONE, took(ReplayReport.class).getLast(), "detached: the report was of the run before");
    }

    @Test
    void aRecordingHasNoLinkToAskAndSaysSo() {
        tell(Messages.RUN_PANEL_TOPIC, attachedTo("replay:rec.csv"));
        tell(Messages.RUN_AGAIN_TOPIC, new RunAgain());
        assertTrue(took(AskReplay.class).isEmpty());
        assertTrue(rerun.state().report().said().startsWith("A recording cannot be run again"),
                rerun.state().report().said());
    }

    @Test
    void eachDifferenceIsReadWithItsTickWhatTheReplaySentAndWhatTheRunDidWhenItKeptIt() {
        ReplayReport r = Rerun.report("w2-1", String.join("\n",
                "ok replay ticks=3 sends=6 differ=17 full",
                "#1 0 test.replay.answered recorded=5e1 replayed=5e4",
                "  now a #4",
                "  was a #1",
                "#2 1 test.replay.reported recorded=none replayed=1f",
                "  now BB",
                "#3 threw java.lang.IllegalStateException: boom"));
        assertEquals(List.of(new Difference(1, 0, "test.replay.answered", "5e1", "5e4", "a #4", "a #1"),
                new Difference(2, 1, "test.replay.reported", "none", "1f", "BB", ""),
                new Difference(3, -1, "", "", "", "the replay threw java.lang.IllegalStateException: boom", "")),
                r.differences());
        assertEquals(List.of(new Difference(1, 0, "t", "5e1", "5e4", "a #4", "")), Rerun.report("w2-1",
                "ok replay ticks=1 sends=1 differ=1\n#1 0 t recorded=5e1 replayed=5e4 a #4").differences(),
                "a run of R6's framework says what the replay sent on the difference's own line");
        assertEquals("Ran 3 ticks of its logic again: 17 of 6 sends differ, the first in tick #1 (3 listed); its"
                + " recording is full, so only the ticks to there", r.said());
        assertEquals("Could not run it again: this run keeps no recording; launch it --replayable",
                Rerun.report("w2-1", "err replay: this run keeps no recording; launch it --replayable").said());
    }
}
