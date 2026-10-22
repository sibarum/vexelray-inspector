package dev.sibarum.inspector.circuit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The declared graph read. */
class BusGraphTest {

    private static BusGraph sample(String file) throws Exception {
        return BusGraph.parse(Files.readString(Path.of("samples", file)));
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
}
