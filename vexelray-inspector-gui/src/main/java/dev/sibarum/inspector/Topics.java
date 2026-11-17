package dev.sibarum.inspector;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Face;
import dev.vexelray.gui.widget.Table;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Every column sorts, a click on its header: a count as a number, so the busiest topics can be put first. The
 * dashboard's first {@code limit} are then the first in that order of every topic, not of those already shown.
 *
 * <p>Its rows are rebuilt only when the topics change, which is once a run, or the order does; the counts, which
 * change with every batch, change in place. Holds what it was last told only to do that and to copy it, as {@link Ui} does.
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
    /** The topics the table was last given to show: the first {@link #limit} in its order. */
    private List<String> shown = List.of();
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
            Face<String> face = (made, topic) -> {
                Node text = made.text(cells.getOrDefault(topic, View.NO_FLOW).get(at))
                        .textSize(Type.SMALL).textColor(at > 0 ? Role.DIM
                                : crossed.contains(topic) ? Ui.PATH : Role.INK);
                nodes.computeIfAbsent(topic, t -> new Node[View.FLOW_COLUMNS.size()])[at] = text;
                return text;
            };
            columns.add(Table.Column.of(View.FLOW_COLUMNS.get(i), WIDTHS.get(i), face, by(at, cells)));
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
            shown = List.of();
            reorder();
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
        reorder();
    }

    /**
     * The table was sorted by a click on a header, or put back in the order given: the first {@link #limit} by that
     * order are found again among every topic, not only among those shown.
     */
    void sorted() {
        reorder();
    }

    /** The topics the table shows, in its order. */
    List<String> shown() {
        return shown;
    }

    /**
     * Show the first {@link #limit} topics in the table's order, when they are not what it shows: when the topics
     * change, the sort does, or the counts move a topic past another in the column sorted by. The table orders what
     * it is given by the same comparator, so the two agree.
     */
    private void reorder() {
        List<String> next = ordered(topics, cells, table.sortedColumn(), table.sortDirection(), limit);
        if (!next.equals(shown)) {
            shown = next;
            nodes.clear();
            table.items(next);
        }
    }

    /**
     * The first {@code limit} of {@code topics} ordered by {@code column} in {@code direction}, each topic's cells as
     * {@code cells} gives them; in the order given while it sorts by none. Pure, for a test.
     */
    static List<String> ordered(List<String> topics, Map<String, List<String>> cells, int column,
                                Table.Sort direction, int limit) {
        List<String> all = new ArrayList<>(topics);
        if (column >= 0 && direction != Table.Sort.NONE) {
            Comparator<String> order = by(column, cells);
            all.sort(direction == Table.Sort.DESCENDING ? order.reversed() : order);
        }
        return List.copyOf(all.subList(0, Math.min(limit, all.size())));
    }

    /** The first of {@link View#FLOW_COLUMNS} that is a count, and every one after it: sorted as numbers. */
    static final int COUNTS_FROM = 4;

    /**
     * How topics compare in {@code column}, by their cells as {@code cells} has them when asked: a count as a number,
     * a blank one (nothing counted) under every number, and a name as words, case aside.
     */
    static Comparator<String> by(int column, Map<String, List<String>> cells) {
        if (column >= COUNTS_FROM) {
            return Comparator.comparingDouble((String topic) -> count(cells.getOrDefault(topic, View.NO_FLOW).get(column)));
        }
        return Comparator.comparing((String topic) -> cells.getOrDefault(topic, View.NO_FLOW).get(column),
                String.CASE_INSENSITIVE_ORDER);
    }

    private static double count(String said) {
        try {
            return said.isEmpty() ? -1.0 : Double.parseDouble(said);
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    /** Mark {@code marked}, the topics the path crossed, by their names in the path's colour, and unmark the rest. */
    void crossed(List<String> marked) {
        Set<String> now = Set.copyOf(marked);
        for (Map.Entry<String, Node[]> e : nodes.entrySet()) {
            boolean was = crossed.contains(e.getKey());
            boolean is = now.contains(e.getKey());
            if (was != is && e.getValue()[0] != null) {
                e.getValue()[0].textColor(is ? Ui.PATH : Role.INK);
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
