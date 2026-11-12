package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.Crossed;
import dev.sibarum.inspector.core.Messages.End;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Part;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph panel's picture, read as its layout and its marks. */
class DiagramTest {

    private static final String P = "dev.example.";

    private static final Flows FLOWS = new Flows("run", "", List.of(
            new Part("view", P + "View", "gui", false),
            new Part("component", P + "Bench", "logic", true),
            new Part("source", P + "LinkRows", "own", false),
            new Part("job", P + "ListRuns", "job", false),
            new Part("component", P + "Physics", "physics", false)), List.of(
            new Flow(P + "Rows", "edge", List.of(new End(P + "LinkRows", "sends", 40)),
                    List.of(new End(P + "Bench", "inbox", 40)), 40, 40, 0.0),
            new Flow(P + "Timeline", "state", List.of(new End(P + "Bench", "sends", 30)),
                    List.of(new End(P + "View#timeline", "mailbox", 10)), 30, 10, 0.0),
            new Flow(P + "Details", "state", List.of(new End(P + "Bench", "sends", 30)),
                    List.of(new End(P + "View#details", "mailbox", 10)), 30, 10, 0.0),
            new Flow(P + "FindRuns", "edge", List.of(new End(P + "Bench", "sends", 0)),
                    List.of(new End(P + "ListRuns", "request", End.UNCOUNTED)), 0, 0, 0.0),
            new Flow("dev.vexelray.framework.core.WindowOpened", "edge", List.of(),
                    List.of(new End(P + "Bench", "inbox", 1)), 0, 1, 0.0)));

    @Test
    void thePartsStandInTiersFromOutsideInAndTheFrameworkIsOneWhenItSendsOrHears() {
        Diagram.Layout l = Diagram.lay(FLOWS, 600f, 600f);
        List<Float> ys = List.of(End.FRAMEWORK, P + "LinkRows", P + "ListRuns", P + "Bench", P + "View").stream()
                .map(p -> l.boxes().get(p).y()).toList();
        for (int i = 1; i < ys.size(); i++) {
            assertTrue(ys.get(i) > ys.get(i - 1), "tier " + i + " below the one before it: " + ys);
        }
        assertEquals(l.boxes().get(P + "Bench").y(), l.boxes().get(P + "Physics").y(), "components share a tier");
        assertEquals("Physics · physics", l.boxes().get(P + "Physics").label(), "a declared lane is named");
        assertEquals("Bench", l.boxes().get(P + "Bench").label());
        assertEquals("Physics", Diagram.fit("Physics · physics", 80f), "a narrow box lets its lane go first");
        assertEquals("Physics · physics", Diagram.fit("Physics · physics", 200f));
    }

    @Test
    void aCrowdedTierWrapsOntoEvenRowsOfNoMoreThanTheMost() {
        assertEquals(List.of(4, 4, 3), Diagram.rows(names(11)).stream().map(List::size).toList());
        assertEquals(List.of(5), Diagram.rows(names(5)).stream().map(List::size).toList());
        assertEquals(List.of(3, 3), Diagram.rows(names(6)).stream().map(List::size).toList());
        assertEquals(List.of(0), Diagram.rows(List.of()).stream().map(List::size).toList());

        // Eleven components and a view: three rows of components above the view, every component below the source.
        List<Part> parts = new java.util.ArrayList<>(List.of(new Part("source", P + "Src", "own", false),
                new Part("view", P + "View", "gui", false)));
        for (String name : names(11)) {
            parts.add(new Part("component", name, "logic", false));
        }
        Diagram.Layout l = Diagram.lay(new Flows("run", "", parts, List.of()), 900f, 600f);
        List<Float> ys = names(11).stream().map(p -> l.boxes().get(p).y()).distinct().sorted().toList();
        assertEquals(3, ys.size(), "eleven components in three rows: " + ys);
        assertTrue(l.boxes().get(P + "Src").y() < ys.get(0));
        assertTrue(l.boxes().get(P + "View").y() > ys.get(2));
    }

    private static List<String> names(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> P + "Part" + (char) ('A' + i)).toList();
    }

    @Test
    void anArrowIsAPairOfPartsWithEveryTopicItCarriesAndIsLitByWhatItsSenderWrote() {
        Diagram.Layout l = Diagram.lay(FLOWS, 600f, 600f);
        Diagram.Arrow toView = l.arrows().stream().filter(a -> a.to().equals(P + "View")).findFirst().orElseThrow();
        assertEquals(P + "Bench", toView.from(), "a mailbox is its part's");
        assertEquals(List.of("Timeline", "Details"), toView.topics());
        assertEquals(60, toView.written());
        assertTrue(toView.lit());
        Diagram.Arrow ask = l.arrows().stream().filter(a -> a.to().equals(P + "ListRuns")).findFirst().orElseThrow();
        assertFalse(ask.lit(), "declared, and nothing crossed it yet");
        assertTrue(l.arrows().stream().anyMatch(a -> a.from().equals(End.FRAMEWORK) && a.to().equals(P + "Bench")),
                "a topic no declared part sends is the framework's");
    }

    @Test
    void theTopicsAreNamedOnALitArrowAndNotOnAFaintOne() {
        Picture p = Diagram.draw(FLOWS, 600f, 600f, INKS);
        List<String> texts = p.marks().stream().filter(m -> m instanceof Picture.Text).map(m -> ((Picture.Text) m)
                .text()).toList();
        assertTrue(texts.contains("Timeline, Details"), texts.toString());
        assertTrue(texts.contains("Rows"), texts.toString());
        assertFalse(texts.contains("FindRuns"), "a faint arrow names nothing: " + texts);
        assertTrue(texts.containsAll(List.of("Bench", "View", "LinkRows", "ListRuns", End.FRAMEWORK)), texts.toString());
        assertEquals(0, Diagram.draw(FLOWS, 0f, 0f, INKS).marks().size(), "nothing before the panel is laid out");
    }

    private static final Color PATH = Color.rgb(1, 1, 0);
    private static final Diagram.Inks INKS = new Diagram.Inks(Color.WHITE, Color.WHITE, Color.rgb(1, 0, 0),
            Color.rgb(0, 1, 0), Color.WHITE, PATH);

    @Test
    void theArrowsAPathWasDeliveredAlongAreDrawnInItsColourAndNamedThoughFaint() {
        Traced t = new Traced("run", "c", List.of(), List.of(), List.of(), List.of(
                new Crossed(P + "Rows", ""), new Crossed(P + "Rows", P + "Bench"),
                new Crossed(P + "FindRuns", P + "ListRuns")));
        Diagram.Layout l = Diagram.lay(FLOWS, 600f, 600f);
        List<String> on = l.arrows().stream().filter(a -> Diagram.onPath(a, l, t)).map(a ->
                Model.typeName(a.from()) + ">" + Model.typeName(a.to())).toList();
        assertEquals(List.of("LinkRows>Bench", "Bench>ListRuns"), on, "by topic and receiver; a write alone is no arrow");
        Picture p = Diagram.draw(FLOWS, t, 600f, 600f, INKS);
        List<String> texts = p.marks().stream().filter(m -> m instanceof Picture.Text).map(m -> ((Picture.Text) m)
                .text()).toList();
        assertTrue(texts.contains("FindRuns"), "named on the path though nothing else crossed it: " + texts);
        assertEquals(6, p.marks().stream().filter(m -> m instanceof Picture.Line line && line.color().equals(PATH))
                .count(), "two arrows, each a line and a head of two strokes");
    }

    @Test
    void anArrowSaysWhatItsSenderWritesASecondAndHowLongAgoItLastDidAndOnlyABusyOneHasATrack() {
        long at = 10_000_000_000L;
        Flows f = new Flows("run", "", FLOWS.parts(), List.of(
                new Flow(P + "Rows", "edge", List.of(new End(P + "LinkRows", "sends", 40, 25.0, at - 300_000_000L)),
                        List.of(new End(P + "Bench", "inbox", 40)), 40, 40, 25.0),
                new Flow(P + "Timeline", "state", List.of(new End(P + "Bench", "sends", 30, 2.5, at - 2_500_000_000L)),
                        List.of(new End(P + "View#timeline", "mailbox", 10)), 30, 10, 2.5),
                new Flow(P + "Details", "state", List.of(new End(P + "Bench", "sends", 30, 0.0, at - 4_000_000_000L)),
                        List.of(new End(P + "View#details", "mailbox", 10)), 30, 10, 0.0)),
                java.util.Optional.empty(), at);
        Diagram.Layout l = Diagram.lay(f, 600f, 600f);
        Diagram.Arrow toView = l.arrows().stream().filter(a -> a.to().equals(P + "View")).findFirst().orElseThrow();
        assertEquals(2.5, toView.perSecond(), 1e-9, "the topics' rates on one arrow together");
        assertEquals(at - 2_500_000_000L, toView.lastNanos(), "and the newest of their last writes");

        List<String> texts = Diagram.draw(f, 600f, 600f, INKS).marks().stream()
                .filter(m -> m instanceof Picture.Text).map(m -> ((Picture.Text) m).text()).toList();
        assertTrue(texts.contains("Rows · 25/s · 300 ms ago"), texts.toString());
        assertTrue(texts.contains("Timeline, Details · 2.5/s · 2.5 s ago"), texts.toString());
        assertEquals("", Diagram.measured(new Diagram.Arrow("a", "b", List.of("T"), 3), 0L),
                "nothing said that is not known");
        assertEquals("75 s ago", Diagram.ago(75_400_000_000L));

        Diagram.Tracks tracks = Diagram.tracks(f, 600f, 600f);
        assertEquals(l.arrows().size(), tracks.all().size(), "every arrow is a track, busy or not");
        Diagram.Track rows = tracks.carrying("Rows", P + "LinkRows").getFirst();
        assertEquals(List.of(P + "LinkRows", P + "Bench"), List.of(rows.from(), l.arrows().stream()
                .filter(a -> a.from().equals(rows.from())).findFirst().orElseThrow().to()));
        assertTrue(rows.length() > 0f);
        assertEquals(Diagram.Tracks.NONE, Diagram.tracks(f, 0f, 0f));
    }
}
