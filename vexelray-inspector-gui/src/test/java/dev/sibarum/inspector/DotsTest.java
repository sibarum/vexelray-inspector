package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Field;
import dev.sibarum.inspector.core.Messages.Mark;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph's dots: a message each, put on its arrow by its type and sender, and placed by when it was written. */
class DotsTest {

    private static final long MS = 1_000_000L;
    private static final long S = 1_000_000_000L;

    /** What the asked half set, a call at a time: the x and y of each offset, and each opacity. */
    private static final class Seen implements Drawn {
        final List<float[]> offsets = new ArrayList<>();
        final List<Float> opacities = new ArrayList<>();

        @Override
        public Drawn offset(Node node, float dx, float dy) {
            offsets.add(new float[]{dx, dy});
            return this;
        }

        @Override
        public Drawn stretch(Node node, float factor, float originX) {
            return this;
        }

        @Override
        public Drawn scale(Node node, float factor) {
            return this;
        }

        @Override
        public Drawn opacity(Node node, float opacity) {
            opacities.add(opacity);
            return this;
        }

        @Override
        public Drawn show(Feed feed, int slot) {
            return this;
        }
    }

    private static Mark message(long at, String type, String from) {
        return new Mark(at, at, Model.MESSAGE, "vexel-logic", 0L, "", List.of(new Field(Model.TYPE, type),
                new Field(Model.FROM, from)));
    }

    @Test
    void eachDotIsWhereItsMessageIsAFullTransitAfterItWasWrittenItHasLanded() {
        Dots.Dot d = new Dots.Dot(null, 10f, 20f, 1f, 0f, 200f, 5 * S);
        Dots.Dot spare = new Dots.Dot(null, 0f, 0f, 0f, 0f, 0f, 0L);
        Dots s = new Dots(List.of(d, spare), -1L);

        Seen half = new Seen();
        Dots.place(s, 5 * S + Dots.TRANSIT / 2, half);
        assertEquals(10f + 100f - Dots.BOX / 2f, half.offsets.getFirst()[0], 1e-3, "half way when half its transit is gone");
        assertEquals(20f - Dots.BOX / 2f, half.offsets.getFirst()[1], 1e-3);
        assertEquals(List.of(1f, 0f), half.opacities, "the spare node carries nothing");

        Seen before = new Seen();
        Dots.place(s, 5 * S - MS, before);
        assertEquals(0f, before.opacities.getFirst(), "not yet written");
        Seen after = new Seen();
        Dots.place(s, 5 * S + Dots.TRANSIT + MS, after);
        assertEquals(0f, after.opacities.getFirst(), "landed");
        Seen near = new Seen();
        Dots.place(s, 5 * S + (long) (Dots.TRANSIT * 197.0 / 200.0), near);
        assertEquals(0.3f, near.opacities.getFirst(), 1e-3, "three px from where it lands");
    }

    @Test
    void followingTheDotsRunAtTheEdgeAndStoppedTheyStandAtTheCursor() {
        Dots.Dot d = new Dots.Dot(null, 0f, 0f, 1f, 0f, 100f, S);
        assertTrue(new Dots(List.of(d), -1L).moving());
        assertEquals(7.0, new Dots(List.of(d), -1L).moment(7.0));
        Dots pinned = new Dots(List.of(d), 3 * S);
        assertFalse(pinned.moving(), "stopped, nothing moves on its own");
        assertEquals(3e9, pinned.moment(7.0), "at the cursor, whatever the edge");
        assertFalse(Dots.NONE.moving());
    }

    @Test
    void aMessageIsPutOnTheArrowOfItsTypeFromItsSenderAndASenderWithNoBoxIsTheFrameworks() {
        Diagram.Track rows = new Diagram.Track("dev.LinkRows", List.of("Messages.Rows"), 0f, 0f, 100f, 0f);
        Diagram.Track replay = new Diagram.Track("dev.RecordingRows", List.of("Messages.Rows"), 0f, 50f, 100f, 50f);
        Diagram.Track pressed = new Diagram.Track(End.FRAMEWORK, List.of("Button.Pressed"), 0f, 90f, 100f, 90f);
        Diagram.Tracks tracks = new Diagram.Tracks(Set.of("dev.LinkRows", "dev.RecordingRows", End.FRAMEWORK),
                List.of(rows, replay, pressed));
        assertEquals(List.of(rows), tracks.carrying("Messages.Rows", "dev.LinkRows"), "two senders told apart");
        assertEquals(List.of(pressed), tracks.carrying("Button.Pressed", "on main"));
        assertEquals(List.of(), tracks.carrying("Messages.Rows", "dev.Elsewhere"));
    }

    @Test
    void aDotForEachMessageOnItsWayAndOneForMessagesTooCloseTogetherToTellApart() {
        Diagram.Track rows = new Diagram.Track("dev.LinkRows", List.of("Messages.Rows"), 0f, 0f, 600f, 0f);
        Diagram.Tracks tracks = new Diagram.Tracks(Set.of("dev.LinkRows"), List.of(rows));
        long cursor = 9 * S + 500 * MS;
        List<Mark> marks = List.of(
                message(S, "Messages.Rows", "dev.LinkRows"),                    // landed long before
                message(9 * S, "Messages.Rows", "dev.LinkRows"),
                message(9 * S + MS, "Messages.Rows", "dev.LinkRows"),           // under a dot's width behind: drawn over
                message(9 * S + 300 * MS, "Messages.Rows", "dev.LinkRows"),
                message(9 * S + 400 * MS, "Messages.Other", "dev.LinkRows"),    // on no arrow
                message(9 * S + 600 * MS, "Messages.Rows", "dev.LinkRows"));    // after the cursor
        assertEquals(List.of(9 * S, 9 * S + 300 * MS), sentAt(Dots.trips(tracks, marks, cursor, cursor)),
                "stopped: those on their way at the cursor");
        assertEquals(List.of(9 * S, 9 * S + 300 * MS, 9 * S + 600 * MS),
                sentAt(Dots.trips(tracks, marks, cursor, Long.MAX_VALUE)), "following: and those written since");
        assertEquals(1, Dots.from(marks, 9 * S));
    }

    private static List<Long> sentAt(List<Dots.Trip> trips) {
        return trips.stream().map(Dots.Trip::sentAt).toList();
    }
}
