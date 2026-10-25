package dev.sibarum.inspector;

import dev.sibarum.inspector.rows.ReplaySource;
import dev.sibarum.inspector.rows.Row;
import dev.sibarum.inspector.rows.RowSink;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Inspector's real tree, built headless by the generated wiring: no window, no GPU, and (because the start
 * recipe takes the {@code Shell}, which a headless build never reaches) no replay thread. The rows are fed by hand
 * instead, through the same sink the replay would use.
 */
class InspectorTreeTest {

    private Shell shell;
    private InspectorAppWiring wiring;

    @BeforeEach
    void build() {
        wiring = new InspectorAppWiring();
        shell = VexelApplication.tree(wiring, new String[] {"--replay=samples/calculator.csv"});
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    @Test
    void theReplayIsTheSourceAndTheTreeBuilds() {
        assertEquals("replay calculator.csv, looping", wiring.source().describe());
        assertNotNull(wiring.ui());
    }

    @Test
    void theWholeSampleRunsThroughTheWindowsListener() throws Exception {
        RowSink sink = wiring.escapement();
        sink.begin("by hand");
        for (Row row : ReplaySource.read(Path.of("samples/calculator.csv"))) {
            sink.row(row);
        }
        sink.end("done");
    }

    /** Headless, nothing ticks the clock: the show is armed and never steps, as when its frame hook was stopped. */
    @Test
    void aShowWhoseClockHasStoppedSaysSo() throws Exception {
        RowSink sink = wiring.escapement();
        java.util.List<Row> rows = ReplaySource.read(Path.of("samples/calculator.csv"));
        sink.begin("by hand");
        sink.row(rows.get(0));
        assertNull(wiring.ui().stopped(), "armed a moment ago is not stopped");
        Thread.sleep(2_100);
        sink.row(rows.get(1));
        assertTrue(wiring.ui().stopped().startsWith("Stopped showing rows"), wiring.ui().stopped());
    }
}
