package dev.sibarum.inspector.circuit;

import dev.sibarum.inspector.rows.Row;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The declared graph read, laid out as a backplane, and the bus lane's rows read into electrons along it. */
class CircuitTest {

    private static BusGraph sample(String file) throws Exception {
        return BusGraph.parse(Files.readString(Path.of("samples", file)));
    }

    private static Row bus(String thread, String kind, String detail) {
        return new Row(0, 0, "-", thread, "bus", kind, detail, Row.NO_FRAME);
    }

    /** What the wire was told, as text. */
    private static final class Told implements Messages.Wire {
        final List<String> said = new ArrayList<>();
        final Layout layout;

        Told(Layout layout) {
            this.layout = layout;
        }

        @Override
        public void electron(int path, int mailbox) {
            float[] xs = layout.pathXs(path);
            boolean fromTrunk = xs.length == 3 && mailbox >= 0;
            said.add("electron " + (mailbox < 0 ? "framework" : layout.mailboxName(mailbox))
                    + (fromTrunk ? " from the trunk" : ""));
        }

        @Override
        public void charge(int mailbox, long depth) {
            said.add("charge " + layout.mailboxName(mailbox) + " " + depth);
        }

        @Override
        public void drain(int mailbox, long count) {
            said.add("drain " + layout.mailboxName(mailbox) + " " + count);
        }

        @Override
        public void spark(int mailbox) {
            said.add("spark " + layout.mailboxName(mailbox));
        }
    }

    @Test
    void theWitnessGraphIsOnePublisherFanningOutToFourComponents() throws Exception {
        BusGraph g = sample("witness-graph.txt");
        assertEquals(5, g.chips().size());
        assertEquals(4, g.mailboxes().size());
        assertEquals(List.of("witness.Ui"), g.publishersOf("witness.job").stream().map(BusGraph.Chip::name).toList());
        assertEquals("witness.Worker", g.mailboxesOf("witness.job").getFirst().owner());
        assertEquals("job", g.mailboxesOf("witness.job").getFirst().method());
        assertTrue(g.declares("witness.ping"));
        assertTrue(!g.declares("vexelray.gui.mutations"), "the framework's topics are not declared");
    }

    @Test
    void anotherVersionOrAnUnknownLineIsRefused() {
        assertThrows(BusGraph.Refused.class, () -> BusGraph.parse("# vexelray bus graph 2\n"));
        assertThrows(BusGraph.Refused.class, () -> BusGraph.parse(BusGraph.HEADER + "\nwire a b=c\n"));
        assertThrows(BusGraph.Refused.class, () -> BusGraph.parse(BusGraph.HEADER
                + "\nmailbox x.Y#z topic=t type=T overflow=FAIL capacity=1\n"), "a mailbox on no chip");
    }

    @Test
    void everyPathRunsFromItsSendersRowAlongItsTrunkToItsPin() throws Exception {
        Layout l = new Layout(sample("circuit-graph.txt"));
        int edit = l.topic("doc.edit");
        assertEquals(3, l.pins(edit).length, "doc.edit fans out to three mailboxes");
        int ui = l.chip("demo.Ui");
        for (int m : l.pins(edit)) {
            int path = l.path(edit, ui, m);
            float[] xs = l.pathXs(path), ys = l.pathYs(path);
            assertEquals(l.trunkX(edit), xs[1], 1e-6, "out to the trunk");
            assertEquals(l.trunkX(edit), xs[2], 1e-6, "along it");
            assertEquals(l.pin(m).x(), xs[3], 1e-6, "in to the pin");
            assertEquals(l.pin(m).y(), ys[3], 1e-6);
        }
        assertEquals(-1, l.path(edit, l.chip("demo.Logger"), l.pins(edit)[0]), "Logger does not publish doc.edit");
        // Two columns, nothing overlapping in either.
        for (Layout.ChipBox a : l.chips()) {
            for (Layout.ChipBox b : l.chips()) {
                if (a != b && a.right() == b.right()) {
                    assertTrue(a.y() + a.h() <= b.y() || b.y() + b.h() <= a.y(), a.name() + " overlaps " + b.name());
                }
            }
        }
        float[] out = new float[2];
        int p = l.path(edit, ui, l.pins(edit)[0]);
        l.at(p, 0, out);
        assertEquals(l.pathXs(p)[0], out[0], 1e-6);
        l.at(p, 1, out);
        assertEquals(l.pathXs(p)[3], out[0], 1e-6);
    }

    @Test
    void chipsStandInTheOrderMessagesFlowThroughThem() throws Exception {
        List<String> order = Layout.byFlow(sample("circuit-graph.txt")).stream().map(BusGraph.Chip::label).toList();
        assertEquals("Ui", order.getFirst(), "the one that only sends");
        assertTrue(order.indexOf("Parser") < order.indexOf("Indexer"), order.toString());
        assertTrue(order.indexOf("Indexer") < order.indexOf("Renderer"), order.toString());
        assertEquals("Logger", order.getLast(), "everyone logs");
    }

    @Test
    void enqueueRowsSayWhereAMessageWentAndTheThreadSaysWhoSentIt() throws Exception {
        Layout l = new Layout(sample("circuit-graph.txt"));
        Told told = new Told(l);
        Messages m = new Messages(l, told);
        // The Ui edits: queued to the Parser and the Logger only (the Autosave coalesced it away).
        m.row(bus("main", "enqueue", "demo.Parser#edit 1"));
        m.row(bus("main", "enqueue", "demo.Logger#edit 3"));
        m.row(bus("main", "doc.edit", "4.1us"));
        assertEquals(List.of("charge demo.Parser#edit 1", "charge demo.Logger#edit 3", "electron demo.Parser#edit",
                "electron demo.Logger#edit"), told.said);
        told.said.clear();
        // doc.parsed has two declared publishers: the Parser's lane names it, an unknown thread does not.
        m.row(bus("vexel-component-parse", "doc.parsed", "2us"));
        assertEquals(List.of("electron demo.Indexer#parsed", "electron demo.Renderer#parsed"), told.said,
                "no enqueue rows: every declared mailbox");
        told.said.clear();
        m.row(bus("vexelray-gui-handler-1", "doc.parsed", "2us"));
        assertEquals(List.of("electron demo.Indexer#parsed from the trunk", "electron demo.Renderer#parsed from the trunk"),
                told.said, "one of two: not claimed");
        told.said.clear();
        m.row(bus("main", "vexelray.gui.mutations", "9us"));
        m.row(bus("vexel-component-<default>", "deliver", "demo.Logger#edit 3"));
        m.row(bus("main", "drop", "demo.Indexer#parsed DROP_OLDEST"));
        assertEquals(List.of("electron framework", "drain demo.Logger#edit 3", "spark demo.Indexer#parsed"),
                told.said);
    }
}
