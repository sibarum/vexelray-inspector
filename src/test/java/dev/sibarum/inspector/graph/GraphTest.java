package dev.sibarum.inspector.graph;

import dev.sibarum.inspector.circuit.BusGraph;
import dev.sibarum.inspector.rows.ReplaySource;
import dev.sibarum.inspector.rows.Row;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTest {

    private final List<String> crossed = new ArrayList<>();
    private final Graph graph = new Graph((from, to) -> crossed.add(from + ">" + to));
    private long seq;

    private Row bus(long t, String thread, String kind, String detail) {
        return new Row(++seq, t, "-", thread, "bus", kind, detail, Row.NO_FRAME);
    }

    /** {@code from>to topic total} for every flow, in the order they were made. */
    private List<String> flows() {
        List<String> out = new ArrayList<>();
        for (int f = 0; f < graph.flows(); f++) {
            out.add(graph.from(f) + ">" + graph.to(f) + " " + graph.topic(f) + " " + graph.total(f));
        }
        return out;
    }

    @Test
    void automationIsToldFromADeviceByItsThread() {
        graph.row(bus(1, "main", "tactroller.input", "3.1us"));
        graph.row(bus(2, "vexelray-automation-1", "tactroller.input", "3.1us"));
        graph.row(bus(3, "main", "tactroller.input", "3.1us"));
        assertEquals(List.of(Graph.INPUT + ">" + Graph.GUI + " tactroller.input 2",
                Graph.AUTOMATION + ">" + Graph.GUI + " tactroller.input 1"), flows());
        assertEquals(3, crossed.size());
    }

    @Test
    void aHandlersMutationIsTheAppsAndATimersIsWithinTheGui() {
        graph.row(bus(1, "vexel-handler-4", "vexelray.gui.mutations", "3.9us"));
        graph.row(bus(2, "vexelray-caret-blink", "vexelray.gui.mutations", "3.0us"));
        assertEquals(List.of(Graph.APP + ">" + Graph.GUI + " vexelray.gui.mutations 1",
                Graph.GUI + ">" + Graph.GUI + " vexelray.gui.mutations 1"), flows());
        assertEquals(List.of(Graph.APP + ">" + Graph.GUI), crossed, "a message within a part crosses nothing");
    }

    @Test
    void aNamedMailboxIsItsComponentsLaneAndTheDeclaredGraphNamesIt() throws Exception {
        graph.declare(BusGraph.parse(Files.readString(Path.of("samples/witness-graph.txt"))));
        int worker = -1;
        for (int i = Graph.FIRST_COMPONENT; i < Graph.PARTS; i++) {
            Part p = graph.part(i);
            if (p != null && p.key().equals("component.worker")) {
                worker = i;
            }
        }
        assertEquals("Worker", graph.part(worker).title());
        graph.row(bus(1, "main", "enqueue", "witness.Worker#job 1"));
        graph.row(bus(2, "main", "witness.job", "4.0us"));
        graph.row(bus(3, "vexel-component-worker", "witness.ping", "2.0us"));
        assertEquals(List.of(Graph.APP + ">" + worker + " witness.job 1",
                worker + ">" + Graph.UNKNOWN + " witness.ping 1"), flows());
    }

    @Test
    void aPublishWithNoSubscriberGoesToNobody() {
        graph.row(bus(1, "main", "vexelray.gui.mutations", "1"));
        graph.row(bus(2, "main", "app.thing", "2.0us"));
        assertEquals(List.of(Graph.GUI + ">" + Graph.NOBODY + " vexelray.gui.mutations 1",
                Graph.APP + ">" + Graph.UNKNOWN + " app.thing 1"), flows());
        assertTrue(crossed.isEmpty());
    }

    @Test
    void aRateIsMessagesASecondAndIsHeldOverAboutASecond() {
        for (int i = 0; i < 10; i++) {
            graph.row(bus(i * 1_000_000L, "main", "tactroller.input", "3.1us"));
        }
        long last = 9_000_000L;
        assertEquals(10, graph.rate(0, last), 0.2);
        assertEquals(10 / Math.E, graph.rate(0, last + Graph.TAU), 0.1);
        assertEquals(10, graph.total(0), "the total stays");
    }

    @Test
    void theCalculatorSampleShowsInputAutomationTheAppAndTheGuiTalking() throws Exception {
        for (Row row : ReplaySource.read(Path.of("samples/calculator.csv"))) {
            graph.row(row);
        }
        List<String> seen = new ArrayList<>();
        for (int f = 0; f < graph.flows(); f++) {
            seen.add(graph.from(f) + ">" + graph.to(f) + " " + graph.topic(f));
        }
        assertTrue(seen.contains(Graph.INPUT + ">" + Graph.GUI + " tactroller.input"), seen::toString);
        assertTrue(seen.contains(Graph.AUTOMATION + ">" + Graph.GUI + " tactroller.input"), seen::toString);
        assertTrue(seen.contains(Graph.APP + ">" + Graph.GUI + " vexelray.gui.mutations"), seen::toString);
        assertTrue(seen.contains(Graph.GUI + ">" + Graph.GUI + " vexelray.gui.mutations"), seen::toString);
        assertEquals(0, graph.unplaced());
    }
}
