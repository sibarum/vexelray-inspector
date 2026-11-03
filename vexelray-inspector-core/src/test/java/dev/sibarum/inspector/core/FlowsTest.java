package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Messages.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declared graph a run's link says, and what crossed it, counted from the rows: the graph and flows panels'
 * model (design.md section 4, docs/bus-graph.md).
 */
class FlowsTest {

    private static final long MS = 1_000_000L;
    private static final String M = "dev.sibarum.inspector.core.Messages.";

    static final String GRAPH = String.join("\n",
            "# vexelray bus graph 2",
            "part component dev.sibarum.inspector.core.Bench lane=logic timer",
            "part job dev.sibarum.inspector.core.ListRuns lane=job",
            "part source dev.sibarum.inspector.core.LinkRows lane=own",
            "part view dev.sibarum.inspector.View lane=gui",
            "hears dev.sibarum.inspector.View#timeline topic=" + M + "Timeline by=mailbox capacity=64",
            "hears dev.sibarum.inspector.core.Bench topic=" + M + "Rows by=inbox",
            "hears dev.sibarum.inspector.core.Bench topic=" + M + "Runs by=inbox",
            "hears dev.sibarum.inspector.core.ListRuns topic=" + M + "FindRuns by=request",
            "hears dev.sibarum.inspector.core.LinkRows topic=" + M + "Attach by=start",
            "sends dev.sibarum.inspector.core.Bench topic=" + M + "FindRuns",
            "sends dev.sibarum.inspector.core.Bench topic=" + M + "Timeline",
            "sends dev.sibarum.inspector.core.LinkRows topic=" + M + "Rows",
            "sends dev.sibarum.inspector.core.ListRuns topic=" + M + "Runs",
            "topic " + M + "FindRuns kind=edge",
            "topic " + M + "Attach kind=edge keyed",
            "topic " + M + "Rows kind=edge",
            "topic " + M + "Runs kind=edge",
            "topic " + M + "Timeline kind=state",
            "lane logic thread=vexel-logic");

    private static Row graph(long at, String text) {
        return new Row(-1, at, "", Model.APP_LANE, Model.GRAPH, text);
    }

    private static Row send(long seq, long at, String topic, String from) {
        return new Row(seq, at, 0L, 3L, "vexel-logic", "bus", "send", M + topic + " from " + from + " 1", "");
    }

    private static Row receive(long seq, long at, String topic, String to) {
        return new Row(seq, at, 0L, 3L, "main", "bus", "receive", M + topic + " to " + to + " 0", "");
    }

    private static Row publish(long seq, long at, String topic, String thread) {
        return new Row(seq, at, 0L, 3L, thread, "bus", M + topic, "12us", "");
    }

    @Test
    void theGraphIsReadForWhatThisVersionSaysAndALaterLineIsPassedOver() {
        Declared d = Declared.parse(GRAPH);
        assertEquals(new Part("component", "dev.sibarum.inspector.core.Bench", "logic", true), d.parts().getFirst());
        assertEquals(4, d.parts().size());
        assertEquals(new Declared.Line("dev.sibarum.inspector.View#timeline", M + "Timeline", "mailbox"),
                d.ends().getFirst());
        assertEquals(9, d.ends().size());
        assertEquals("edge keyed", d.kinds().get(M + "Attach"));
        assertEquals("state", d.kinds().get(M + "Timeline"));
        assertEquals("", d.why());
        assertTrue(Declared.parse("# vexelray bus graph 1\ncomponent x lane=<default>").why()
                .startsWith("its bus graph is not one this Inspector reads"));
    }

    @Test
    void eachTopicSaysItsEndsAndWhatCrossedThemWithWritesFromOutsideATickPutToThePartThatMakesThem() {
        long at = 2_000 * MS;
        List<Row> rows = new ArrayList<>(List.of(graph(at, GRAPH)));
        for (int i = 0; i < 10; i++) {
            rows.add(send(i, at + i * MS, "Timeline", "dev.sibarum.inspector.core.Bench"));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(receive(20 + i, at + i * MS, "Timeline", "widgets dev.sibarum.inspector.View#timeline"));
        }
        rows.add(publish(30, at + 2 * MS, "Rows", "vexel-source-link-rows"));
        rows.add(receive(31, at + 2 * MS, "Rows", "dev.sibarum.inspector.core.Bench"));
        rows.add(publish(32, at + 3 * MS, "FindRuns", "vexel-timer"));
        rows.add(send(33, at + 3 * MS, "FindRuns", "dev.sibarum.inspector.core.Bench"));

        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        Flows f = m.flows("run", "", Model.EMPTY, 0L);
        assertEquals("", f.why());
        assertEquals(4, f.parts().size());
        assertEquals(List.of(M + "Attach", M + "FindRuns", M + "Rows", M + "Runs", M + "Timeline"),
                f.flows().stream().map(Flow::topic).toList(), "every topic the graph names, by name");

        Flow timeline = f.flows().getLast();
        assertEquals("state", timeline.kind());
        assertEquals(10, timeline.written());
        assertEquals(4, timeline.delivered(), "a state topic written ten times and heard four");
        assertEquals(List.of(new End("dev.sibarum.inspector.core.Bench", "sends", 10)), timeline.from());
        assertEquals(List.of(new End("dev.sibarum.inspector.View#timeline", "mailbox", 4)), timeline.to());

        Flow rowsFlow = f.flows().get(2);
        assertEquals(List.of(new End("dev.sibarum.inspector.core.LinkRows", "sends", 1)), rowsFlow.from(),
                "a source's publish names nobody, and is the source's");
        assertEquals(List.of(new End("dev.sibarum.inspector.core.Bench", "inbox", 1)), rowsFlow.to());

        Flow find = f.flows().get(1);
        assertEquals(2, find.written());
        assertEquals(List.of(new End("dev.sibarum.inspector.core.Bench", "sends", 2)), find.from(),
                "the bench's own send and its timer's, together");
        assertEquals(List.of(new End("dev.sibarum.inspector.core.ListRuns", "request", End.UNCOUNTED)), find.to(),
                "what reaches a job is handed to it by the framework, and no row counts it");

        Flow attach = f.flows().getFirst();
        assertEquals("edge keyed", attach.kind());
        assertEquals(List.of(), attach.from(), "sent by no declared part, and by nothing yet");
        assertEquals(0, attach.written());
    }

    @Test
    void inARunWhoseJobsWriteRowsWhatReachesAJobIsCountedAndItsRunIsOnTheJobsLane() {
        long at = 2_000 * MS;
        List<Row> rows = new ArrayList<>(List.of(graph(at, GRAPH)));
        rows.add(send(1, at, "FindRuns", "dev.sibarum.inspector.core.Bench"));
        rows.add(new Row(2, at + MS, 0L, 3L, "vexel-job-1", "bus", "receive",
                M + "FindRuns to dev.sibarum.inspector.core.ListRuns 0", ""));
        rows.add(new Row(3, at + 3 * MS, 0L, 3L, "vexel-job-1", "bus", "job ListRuns", "2ms", ""));
        rows.add(new Row(4, at + 4 * MS, 0L, 3L, "vexel-timer", "bus", "timer", M + "FindRuns", ""));
        Model m = Model.EMPTY.fold(rows, Long.MAX_VALUE);
        Flow find = m.flows("run", "", Model.EMPTY, 0L).flows().get(1);
        assertEquals(List.of(new End("dev.sibarum.inspector.core.ListRuns", "request", 1)), find.to(),
                "its request's receive row counts it");
        Messages.Mark job = m.lane(Model.JOBS).marks().getFirst();
        assertEquals(List.of(Model.JOB, at + MS, at + 3 * MS, "ListRuns", "vexel-job-1"),
                List.of(job.kind(), job.fromNanos(), job.toNanos(), job.field(Model.TYPE), job.thread()));
        Messages.Mark timer = m.lane(Model.INPUT).marks().getFirst();
        assertEquals(List.of(Model.TIMER, "Messages.FindRuns"), List.of(timer.kind(), timer.field(Model.TYPE)),
                "what a timer sent, from outside the logic, as input is");
    }

    @Test
    void aWriteFromOutsideATickIsPutToTheSenderWhoseThreadWroteIt() {
        long at = 2_000 * MS;
        String graph = String.join("\n", "# vexelray bus graph 2",
                "part source dev.sibarum.inspector.core.LinkRows lane=own",
                "part source dev.sibarum.inspector.core.RecordingRows lane=own",
                "sends dev.sibarum.inspector.core.LinkRows topic=" + M + "Rows",
                "sends dev.sibarum.inspector.core.RecordingRows topic=" + M + "Rows",
                "topic " + M + "Rows kind=edge");
        Model m = Model.EMPTY.fold(List.of(graph(at, graph),
                publish(1, at, "Rows", "vexel-source-recording-rows"),
                publish(2, at, "Rows", "vexel-source-recording-rows"),
                publish(3, at, "Rows", "vexel-source-link-rows"),
                publish(4, at, "Rows", "vexel-job-1")), Long.MAX_VALUE);
        Model jobs = Model.EMPTY.fold(List.of(graph(at, GRAPH), publish(1, at, "Runs", "vexel-job-3"),
                publish(2, at, "Runs", "vexel-job-7")), Long.MAX_VALUE);
        assertEquals(List.of(new End("dev.sibarum.inspector.core.ListRuns", "sends", 2)),
                jobs.flows("run", "", Model.EMPTY, 0L).flows().get(3).from(), "the job lane's threads are one sender");
        assertEquals(List.of(new End("dev.sibarum.inspector.core.LinkRows", "sends", 1),
                        new End("dev.sibarum.inspector.core.RecordingRows", "sends", 2),
                        new End(End.FRAMEWORK, "sends", 1)),
                m.flows("run", "", Model.EMPTY, 0L).flows().getFirst().from().stream()
                        .sorted(java.util.Comparator.comparing(End::part)).toList(),
                "two sources of one topic told apart by their threads; a job's thread is neither's");
        assertTrue(Model.writesOn(new Part("component", "dev.example.Bench", "logic", true), "vexel-timer"));
        assertTrue(!Model.writesOn(new Part("component", "dev.example.Bench", "logic", false), "vexel-timer"));
    }

    @Test
    void writesNoDeclaredPartMakesAreTheFrameworksAndAReceiverNotDeclaredIsShownByItsName() {
        long at = 2_000 * MS;
        String graph = String.join("\n", "# vexelray bus graph 2",
                "part view dev.example.Screen lane=gui",
                "hears dev.example.Screen#pressed topic=dev.vexelray.gui.widget.Button.Pressed by=mailbox capacity=64",
                "topic dev.vexelray.gui.widget.Button.Pressed kind=edge keyed");
        Model m = Model.EMPTY.fold(List.of(graph(at, graph),
                new Row(1, at, 0L, 3L, "main", "bus", "dev.vexelray.gui.widget.Button.Pressed", "3us", ""),
                new Row(2, at, 0L, 3L, "main", "bus", "receive",
                        "dev.vexelray.gui.widget.Button.Pressed to widgets dev.example.Screen#pressed 0", ""),
                new Row(3, at, 0L, 3L, "main", "bus", "receive",
                        "dev.vexelray.gui.widget.Button.Pressed to elsewhere 0", "")), Long.MAX_VALUE);
        Flow pressed = m.flows("run", "", Model.EMPTY, 0L).flows().getFirst();
        assertEquals(List.of(new End(End.FRAMEWORK, "sends", 1)), pressed.from());
        assertEquals(List.of(new End("dev.example.Screen#pressed", "mailbox", 1), new End("elsewhere", "", 1)),
                pressed.to());
    }

    @Test
    void aRunThatDeclaredNoGraphShowsTheTopicsItsRowsCountedAndSaysWhy() {
        long at = 2_000 * MS;
        Model m = Model.EMPTY.fold(List.of(
                graph(at, "it declared no bus graph (no bus graph: none was declared)"),
                send(1, at, "Timeline", "dev.example.Bench")), Long.MAX_VALUE);
        Flows f = m.flows("run", "", Model.EMPTY, 0L);
        assertEquals("it declared no bus graph (no bus graph: none was declared)", f.why());
        assertEquals(List.of(), f.parts());
        assertEquals(new Flow(M + "Timeline", "", List.of(new End("dev.example.Bench", "sends", 1)), List.of(), 1, 0,
                0.0), f.flows().getFirst());
    }

    @Test
    void theRateIsWhatWasWrittenSinceTheEarlierModelOverHowLongBefore() {
        long at = 2_000 * MS;
        List<Row> first = new ArrayList<>(List.of(graph(at, GRAPH)));
        for (int i = 0; i < 5; i++) {
            first.add(send(i, at + i * MS, "Timeline", "dev.sibarum.inspector.core.Bench"));
        }
        Model before = Model.EMPTY.fold(first, Long.MAX_VALUE);
        List<Row> more = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            more.add(send(10 + i, at + 500 * MS + i * MS, "Timeline", "dev.sibarum.inspector.core.Bench"));
        }
        Model after = before.fold(more, Long.MAX_VALUE);
        Flow timeline = after.flows("run", "", before, 1_500 * MS).flows().getLast();
        assertEquals(35, timeline.written());
        assertEquals(20.0, timeline.perSecond(), 1e-9, "30 written over a second and a half");
    }
}
