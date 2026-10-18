package dev.sibarum.inspector.rows;

import dev.vexelray.gui.automation.Discovery;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** What {@code --attach} matches, what the picker offers, and the source for the one chosen. */
class AttachTest {

    private static Discovery.Entry app(String app, long pid, String stream) {
        Map<String, String> facts = new java.util.LinkedHashMap<>(Map.of("app", app, "pid", Long.toString(pid),
                "started", "2026-10-17T23:0" + (pid % 10) + ":00Z"));
        if (stream != null) {
            facts.put("stream", stream);
        }
        return new Discovery.Entry(Path.of(app + "-" + pid + ".auto"), facts);
    }

    private static final Discovery.Entry DEMO = app("demo", 18292, "uds:C:\\x\\demo-18292.sock");
    private static final Discovery.Entry CALC = app("calculator", 25984, "tcp:61200");
    private static final Discovery.Entry CALC2 = app("calculator", 25990, "tcp:61201");
    private static final Discovery.Entry OLD = app("pix", 4242, null);
    private static final Discovery.Entry SELF = app("vexelray-inspector", ProcessHandle.current().pid(), "tcp:1");

    @Test
    void aNameAPidOrNothingAndNeverThisProcess() {
        List<Discovery.Entry> live = List.of(DEMO, CALC, CALC2, OLD, SELF);
        assertEquals(List.of(DEMO, CALC, CALC2, OLD), Attach.match(live, ""));
        assertEquals(List.of(CALC, CALC2), Attach.match(live, "Calculator"));
        assertEquals(List.of(CALC2), Attach.match(live, "pid:25990"));
        assertEquals(List.of(), Attach.match(live, "vexelray-inspector"), "the Inspector does not list itself");
    }

    @Test
    void oneMatchIsTheLiveSourceAndAnythingElseSaysWhyAndShowsThePicker() {
        List<Discovery.Entry> live = new ArrayList<>(List.of(DEMO, CALC, CALC2, OLD));
        RowSource demo = Attach.initial(live, "demo");
        assertInstanceOf(LinkSource.class, demo);
        assertEquals("demo (pid 18292)", demo.describe());
        assertEquals("no source", Attach.initial(live, "calculator").describe());
        assertEquals(List.of("end --attach=calculator matches 2 applications; choose one"),
                ends(Attach.initial(live, "calculator")));
        assertEquals(List.of("end --attach=mainframe matches no running application; choose one"),
                ends(Attach.initial(live, "mainframe")));
        assertEquals(List.of("end pix (pid 4242) has no inspector link: it was started with -Dautomation.link=off, "
                + "or before the link existed"), ends(Attach.initial(live, "pix")));
    }

    @Test
    void anEntryHasALandmarkOfItsOwn() {
        assertEquals("picker.demo-18292", Attach.landmark(DEMO));
    }

    /** What a source with nothing to deliver says when started. */
    private static List<String> ends(RowSource none) {
        List<String> heard = new ArrayList<>();
        none.start(new RowSink() {
            @Override
            public void begin(String origin) {
                heard.add("begin " + origin);
            }

            @Override
            public void row(Row row) {
                heard.add("row");
            }

            @Override
            public void end(String why) {
                heard.add("end " + why);
            }
        });
        return heard;
    }
}
