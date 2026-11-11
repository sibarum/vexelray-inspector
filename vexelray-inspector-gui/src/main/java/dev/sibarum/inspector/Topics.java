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
import java.util.Optional;
import java.util.Set;

/**
 * The flows table: a row a topic, its kind, its ends and its counts, the names of the topics the path of the event the
 * cursor is on crossed in the path's colour. On the dashboard it shows the first {@code limit} topics, as many as fit
 * without a scrollbar; in the topics window, all of them.
 *
 * <p>Its rows are rebuilt only when the topics change, which is once a run; the counts, which change with every
 * batch, change in place. Holds what it was last told only to do that and to copy it, as {@link Ui} does.
 */
final class Topics {

    /** A row of the flows table, in rem. */
    static final float ROW_REM = 1.5f;

    /** The column widths, in {@link View#FLOW_COLUMNS}' order: the names share what is left. */
    private static final List<Length> WIDTHS = List.of(Length.grow(2f), Length.rem(5.5f), Length.grow(2f),
            Length.grow(3f), Length.rem(6.5f), Length.rem(6.5f), Length.rem(7f));

    private final Gui gui;
    private final Table<String> table;
    private final int limit;
    /** The topics the path crossed, whose names are marked. */
    private Set<String> crossed = Set.of();
    /** The topics, in the order last given; the first {@link #limit} of them are the table's rows. */
    private List<String> topics = List.of();
    /** What each topic's cells say, by topic: what a row realized later is built with. */
    private final Map<String, List<String>> cells = new HashMap<>();
    /** Each realized cell's text, by topic and column. */
    private final Map<String, Node[]> nodes = new HashMap<>();

    /** The table named {@code name}, its body {@code body}, showing at most {@code limit} topics. */
    Topics(Gui gui, String name, String body, int limit) {
        this.gui = gui;
        this.limit = limit;
        List<Table.Column<String>> columns = new ArrayList<>();
        for (int i = 0; i < View.FLOW_COLUMNS.size(); i++) {
            int at = i;
            columns.add(Table.Column.of(View.FLOW_COLUMNS.get(i), WIDTHS.get(i), (made, topic) -> {
                Node text = made.text(cells.getOrDefault(topic, View.NO_FLOW).get(at))
                        .textSize(Type.SMALL).textColor(gui.theme().color(at > 0 ? Role.DIM
                                : crossed.contains(topic) ? Ui.PATH : Role.INK));
                nodes.computeIfAbsent(topic, t -> new Node[View.FLOW_COLUMNS.size()])[at] = text;
                return text;
            }));
        }
        table = new Table<>(gui, ROW_REM, columns).name(name);
        table.rows().name(body).action(Ui.COPY);
        table.headers(Type.UI, Type.SMALL, Role.DIM);
        gui.landmark(name, table.node());
    }

    Table<String> table() {
        return table;
    }

    Node node() {
        return table.node();
    }

    /** Where the body is on the bus: what its clicks and Copy carry. */
    String address() {
        return table.rows().address();
    }

    /** How many topics there are, shown or not. */
    int count() {
        return topics.size();
    }

    /** A row a topic of {@code given}, the first {@link #limit} shown, each cell as {@code given} gives it by topic. */
    void set(List<String> given, Map<String, List<String>> by) {
        if (!given.equals(topics)) {
            topics = List.copyOf(given);
            cells.clear();
            cells.putAll(by);
            nodes.clear();
            table.items(topics.subList(0, Math.min(limit, topics.size())));
            return;
        }
        for (String topic : given) {
            List<String> now = by.get(topic);
            List<String> was = cells.put(topic, now);
            Node[] shown = nodes.get(topic);
            if (now == null || now.equals(was) || shown == null) {
                continue;
            }
            for (int i = 0; i < shown.length; i++) {
                if (shown[i] != null && (was == null || !now.get(i).equals(was.get(i)))) {
                    shown[i].text(now.get(i));
                }
            }
        }
    }

    /** Mark {@code marked}, the topics the path crossed, by their names in the path's colour, and unmark the rest. */
    void crossed(List<String> marked) {
        Set<String> now = Set.copyOf(marked);
        for (Map.Entry<String, Node[]> e : nodes.entrySet()) {
            boolean was = crossed.contains(e.getKey());
            boolean is = now.contains(e.getKey());
            if (was != is && e.getValue()[0] != null) {
                e.getValue()[0].textColor(gui.theme().color(is ? Ui.PATH : Role.INK));
            }
        }
        crossed = now;
    }

    /** The topics {@link #crossed} marked last. */
    Set<String> crossed() {
        return crossed;
    }

    /** What a topic's cells say: empty when it has none. */
    List<String> cells(String topic) {
        return cells.getOrDefault(topic, List.of());
    }

    /** The selected topics' cells, as CSV under the column titles, in the table's order; empty when none is. */
    Optional<String> copied() {
        Set<String> selected = table.selection().selection();
        List<List<String>> chosen = new ArrayList<>();
        for (String topic : topics) {
            List<String> row = cells.get(topic);
            if (selected.contains(topic) && row != null) {
                chosen.add(row);
            }
        }
        return chosen.isEmpty() ? Optional.empty()
                : Optional.of(dev.sibarum.inspector.core.Csv.of(View.FLOW_COLUMNS, chosen));
    }

    /** Whether {@code item} alone is selected: what a plain click leaves (FN-19). */
    boolean alone(Object item) {
        Set<String> selected = table.selection().selection();
        return selected.size() == 1 && selected.contains(item);
    }
}
