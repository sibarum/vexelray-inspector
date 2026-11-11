package dev.sibarum.inspector;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A table of fixed places whose text changes in place: the places stay, so a new set of rows rebuilds nothing, it only
 * changes the cells that differ. The replay panel's, on the dashboard and in its window, each with its own count of
 * places: as many as the dashboard shows without a scrollbar, or all of them where there is room to scroll.
 *
 * <p>Never focused: a focused list claims Space and the arrows, which pause and step here (FN-11). Holds what it was
 * last told only to change it in place and to copy it, as {@link Ui} does.
 */
final class Places {

    private final Gui gui;
    private final Table<Integer> table;
    private final List<String> columns;
    /** What each place says, a cell a column, as {@link #set} last set them. */
    private List<List<String>> texts = List.of();
    /** Each realized cell's text, by place and column. */
    private final Map<Integer, Node[]> nodes = new HashMap<>();

    /**
     * {@code places} places under {@code columns}, as wide as {@code widths}, each column's text in the ink
     * {@code ink} gives it; the table named {@code name} and its body {@code body}, which its clicks carry.
     */
    Places(Gui gui, String name, String body, List<String> columns, List<Length> widths, float rowRem, int places,
           IntFunction<Role> ink) {
        this.gui = gui;
        this.columns = columns;
        List<Table.Column<Integer>> built = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int at = i;
            built.add(Table.Column.of(columns.get(i), widths.get(i), (cells, place) -> {
                Node text = cells.text(cell(texts, place, at)).textSize(Type.SMALL)
                        .textColor(gui.theme().color(ink.apply(at)));
                nodes.computeIfAbsent(place, p -> new Node[columns.size()])[at] = text;
                return text;
            }));
        }
        table = new Table<>(gui, rowRem, built).name(name);
        table.rows().name(body).action(Ui.COPY);
        gui.focusable(table.rows().node(), false);
        table.headers(Type.UI, Type.SMALL, Role.DIM);
        table.items(java.util.stream.IntStream.range(0, places).boxed().toList());
        gui.landmark(name, table.node());
    }

    /** The height that shows {@code places} rows of {@code rowRem} and the header, so the table never scrolls. */
    static Length fit(int places, float rowRem) {
        return Length.rem(rowRem * (places + 1) + 0.75f);
    }

    Table<Integer> table() {
        return table;
    }

    Node node() {
        return table.node();
    }

    /** Where the body is on the bus: what its clicks and Copy carry. */
    String address() {
        return table.rows().address();
    }

    /** Each place's cells as {@code texts} gives them; a place past them is blank. */
    void set(List<List<String>> texts) {
        List<List<String>> was = this.texts;
        this.texts = List.copyOf(texts);
        for (Map.Entry<Integer, Node[]> e : nodes.entrySet()) {
            for (int i = 0; i < e.getValue().length; i++) {
                String now = cell(this.texts, e.getKey(), i);
                if (e.getValue()[i] != null && !now.equals(cell(was, e.getKey(), i))) {
                    e.getValue()[i].text(now);
                }
            }
        }
    }

    /** What {@code place} last said, a cell a column: empty when it is blank. */
    List<String> cells(int place) {
        return place < texts.size() ? texts.get(place) : List.of();
    }

    /** The selected places that have text, as CSV under the column titles; empty when none is. */
    java.util.Optional<String> copied() {
        java.util.Set<Integer> selected = table.selection().selection();
        List<List<String>> chosen = new ArrayList<>();
        for (int place = 0; place < texts.size(); place++) {
            if (selected.contains(place)) {
                chosen.add(texts.get(place));
            }
        }
        return chosen.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(dev.sibarum.inspector.core.Csv.of(columns, chosen));
    }

    /** Whether {@code item} alone is selected: what a plain click leaves (FN-19). */
    boolean alone(Object item) {
        java.util.Set<Integer> selected = table.selection().selection();
        return selected.size() == 1 && selected.contains(item);
    }

    private static String cell(List<List<String>> texts, int place, int column) {
        return place < texts.size() ? texts.get(place).get(column) : "";
    }
}
