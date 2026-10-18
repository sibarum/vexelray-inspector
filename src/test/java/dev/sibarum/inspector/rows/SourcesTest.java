package dev.sibarum.inspector.rows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Switching source at runtime: what the sink and the listener hear, and in what order. */
class SourcesTest {

    /** A source that begins, delivers one row and ends when started, and records whether it was closed. */
    private static final class Once implements RowSource {
        final String name;
        final boolean resumes;
        boolean closed;

        Once(String name, boolean resumes) {
            this.name = name;
            this.resumes = resumes;
        }

        @Override
        public void start(RowSink sink) {
            sink.begin(name);
            sink.row(new Row(1, 1, "-", "main", "frame", "frame.present", "#1", 1));
            sink.end("end of " + name);
        }

        @Override
        public String describe() {
            return name;
        }

        @Override
        public boolean resumes() {
            return resumes;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void attachingClosesTheOldStartsTheNewOnTheSameSinkAndTellsTheListener() {
        List<String> heard = new ArrayList<>();
        Once first = new Once("first", false);
        Once second = new Once("second", false);
        Sources sources = Sources.of(first);
        sources.listen(new Sources.Listener() {
            @Override
            public void attached(RowSource now) {
                heard.add("attached " + now.describe());
            }

            @Override
            public void began(String origin) {
                heard.add("began " + origin);
            }

            @Override
            public void ended(String why) {
                heard.add("ended " + why);
            }
        });
        List<String> sink = new ArrayList<>();
        sources.start(new RowSink() {
            @Override
            public void begin(String origin) {
                sink.add("begin " + origin);
            }

            @Override
            public void row(Row row) {
                sink.add("row");
            }

            @Override
            public void end(String why) {
                sink.add("end " + why);
            }
        });
        sources.attach(second);
        assertTrue(first.closed);
        assertEquals(second, sources.current());
        assertEquals("second", sources.describe());
        assertEquals(List.of("begin first", "row", "end end of first", "begin second", "row", "end end of second"), sink);
        assertEquals(List.of("attached first", "began first", "ended end of first",
                "attached second", "began second", "ended end of second"), heard);
    }

    @Test
    void anEndThatWillBeginAgainByItselfIsNotFinal() {
        List<String> heard = new ArrayList<>();
        Sources sources = Sources.of(new Once("looping replay", true));
        sources.listen(new Sources.Listener() {
            @Override
            public void attached(RowSource now) {
            }

            @Override
            public void began(String origin) {
                heard.add("began");
            }

            @Override
            public void ended(String why) {
                heard.add("ended");
            }
        });
        sources.start(new RowSink() {
            @Override
            public void begin(String origin) {
            }

            @Override
            public void row(Row row) {
            }

            @Override
            public void end(String why) {
            }
        });
        assertEquals(List.of("began"), heard, "a looping replay's pause between passes does not bring the picker back");
    }
}
