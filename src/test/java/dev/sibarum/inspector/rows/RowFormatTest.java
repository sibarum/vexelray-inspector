package dev.sibarum.inspector.rows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowFormatTest {

    @Test
    void aProbeRowWithNoHeader() {
        Row r = new RowFormat().parse("3503,1264122000,2026-10-17T18:06:56.981107800Z,main,frame,frame.present,#1");
        assertNotNull(r);
        assertEquals(3503, r.seq());
        assertEquals(1264122000L, r.monoNanos());
        assertEquals("main", r.thread());
        assertEquals("frame", r.lane());
        assertEquals("frame.present", r.kind());
        assertEquals("#1", r.detail());
        assertEquals(Row.NO_FRAME, r.frame());
        assertEquals(1, r.presentedFrame());
    }

    @Test
    void theProbeHeaderIsAdoptedAndNotARow() {
        RowFormat f = new RowFormat();
        assertNull(f.parse("seq,t_mono_ns,t_wall,thread,lane,kind,detail"));
        assertEquals(0, f.skipped());
        assertEquals("loop.park", f.parse("2,5,w,main,frame,loop.park,forever").kind());
    }

    @Test
    void aQuotedDetailKeepsItsCommasAndQuotes() {
        Row r = new RowFormat().parse("7,75,w,main,frame,wake.unwired,\"mutation on Gui@7, no listener, \"\"so\"\"\"");
        assertEquals("mutation on Gui@7, no listener, \"so\"", r.detail());
    }

    @Test
    void anUnquotedDetailWithCommasIsJoinedBackTogether() {
        Row r = new RowFormat().parse("1,4,w,main,app,log.info,a, b,c");
        assertEquals("a, b,c", r.detail());
    }

    @Test
    void theStreamsHeaderIsReadByNameFrameColumnAndAll() {
        RowFormat f = new RowFormat();
        assertNull(f.parse("seq,t_mono_ns,t_wall,frame,thread,lane,kind,detail"));
        Row r = f.parse("9,100,w,42,main,frame,loop.park,200ms");
        assertEquals(42, r.frame());
        assertEquals(42, r.presentedFrame());
        assertEquals("main", r.thread());
        assertEquals("200ms", r.detail());
        Row unstamped = f.parse("10,101,w,,main,bus,topic,x");
        assertEquals(Row.NO_FRAME, unstamped.frame());
    }

    @Test
    void aHeaderWithoutTheProbeColumnsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RowFormat().parse("seq,when,what"));
    }

    @Test
    void tornAndNonRowLinesAreSkippedAndCounted() {
        RowFormat f = new RowFormat();
        assertNull(f.parse(""));
        assertNull(f.parse("4170,1393526"));                       // a run killed mid-write
        assertNull(f.parse("x,1,w,main,frame,frame.present,#1"));   // not a number
        assertEquals(2, f.skipped());
    }

    @Test
    void theSampleReadsInTimeOrder() throws Exception {
        List<Row> rows = ReplaySource.read(Path.of("samples/calculator.csv"));
        assertTrue(rows.size() > 1000, "rows: " + rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).monoNanos() <= rows.get(i).monoNanos(), "out of order at " + i);
        }
        assertEquals(43, rows.stream().filter(r -> r.is("frame", Row.FRAME_PRESENT)).count());
    }
}
