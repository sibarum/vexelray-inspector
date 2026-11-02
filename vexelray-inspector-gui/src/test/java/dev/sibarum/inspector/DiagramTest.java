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
}
