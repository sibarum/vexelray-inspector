package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Bench;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.Table;
import dev.vexelray.gui.widget.TitleBar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The window: the framework's title bar, the timeline, and a status line under both.
 *
 * <h2>The timeline</h2>
 *
 * <p>One lane per clock, each a name beside a band of marks, with the present at the right edge (vexelray-inspector's
 * {@code docs/design.md} section 3), in {@code Model.LANES}' order. The lanes share one time axis, so they are one box
 * that clips, holding one {@link #strip} whose picture the view draws a band a lane: the view slides the strip left as
 * the present moves on, which is the frame contract's asked half, and the box's clip is what keeps the slid strip
 * inside it (a container's clip is anchored, so a child slides within it). The name of the lane the arrows step
 * through is in the text's colour, the others dimmed ({@link #focus}), and the view outlines its band with an
 * overlay on the lanes' box, which the strip's slide does not move. At that lane's right end, past the present, is
 * the button that follows the present again, pressable while the cursor is stopped ({@link #canFollow}).
 *
 * <h2>The run panel</h2>
 *
 * <p>One line over everything else: what is inspected, how it was reached and where its logic runs, then the picker
 * of running applications and the button that detaches ({@link #runPanel}). Neither control takes the focus: a
 * focused control claims Space, and Space pauses here (FN-11), so both are driven by the pointer.
 *
 * <h2>The warning</h2>
 *
 * <p>Over the lanes, a banner with the caution mark, shown only while what the lanes show may be less than whole: a
 * run of another link version, which may not send all the Inspector reads ({@link #warning}).
 *
 * <h2>The details</h2>
 *
 * <p>Right under the lanes, where the eye goes first: a label and a value a line ({@link #details}), what the run's
 * clock said at the cursor and the fields of the event it is on, a row for each field of the event schema, shown only
 * while that event has it. Its height is kept, {@link #DETAILS_REM}, and what does not fit scrolls inside it: a
 * click's path is thirty hops, and the graph and flows under it must stay in view beside the lanes that mark it.
 *
 * <h2>The rows panel</h2>
 *
 * <p>Beside the details, as tall: the raw rows around the cursor, a place a row, the cursor's in the accent and those
 * on the path of the event it is on in the path's colour ({@link #rows}). The places stay and their text changes, so a
 * step rebuilds nothing.
 *
 * <h2>The readouts</h2>
 *
 * <p>One line of numbers under the timeline, over the second before the cursor ({@link #readouts}).
 *
 * <h2>It holds no state</h2>
 *
 * <p>Every label here says what the bench last told the view, and the view tells it ({@link #status}). Nothing is
 * remembered here to be shown later.
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless. The title bar is the framework's; this application places it and supplies every colour in it
 * through {@link Look}.
 */
final class Ui {

    /** The column the lanes' names stand in. Type-relative, so it grows with the zoom like the names. */
    private static final Length NAMES = Length.rem(7);

    /** A lane's height, in rem. */
    private static final float LANE_REM = 2.5f;

    /** What the path of the event the cursor is on is marked in, in every panel: apart from the accent, which every
     * lane and lit arrow is drawn in. */
    static final Role PATH = Role.SUCCESS;

    /** The details panel's height, in rem: the clock, a few fields and a path of a dozen hops before it scrolls. */
    static final float DETAILS_REM = 16f;

    /** A row of the flows table, in rem. */
    private static final float FLOW_ROW_REM = 1.5f;

    /** A row of the rows panel, in rem: eleven fit beside the details with its header. */
    private static final float ROW_REM = 1.25f;

    /** The rows panel's column widths, in {@link View#ROW_COLUMNS}' order: the detail takes what is left. */
    private static final List<Length> ROW_WIDTHS = List.of(Length.rem(7.5f), Length.rem(8f), Length.rem(5f),
            Length.rem(8f), Length.grow(1f), Length.rem(9f));

    /** The flows table's column widths, in {@link View#FLOW_COLUMNS}' order: the names share what is left. */
    private static final List<Length> FLOW_WIDTHS = List.of(Length.grow(2f), Length.rem(5.5f), Length.grow(2f),
            Length.grow(3f), Length.rem(6.5f), Length.rem(6.5f), Length.rem(7f));

    private final Gui gui;
    private final StatusBar status;
    private final Node lane;
    private final Node strip;
    private final Node[] names;
    private final Node readouts;
    private final IconButton follow;
    /** The space over the follow button, a lane high for each lane above the focused one. */
    private final Node above;
    private final Node[] details;
    private final Node[] detailRows;
    private final Node warning;
    private final Node warningText;
    private final Node runText;
    private final Select<Run> runs;
    private final Button detach;
    private final Table<String> flows;
    private final Node flowsWhy;
    private final Node graph;
    private final Table<Integer> rows;
    /** What each of the rows panel's places says, a cell a column, and in what ink, as {@link #rows} last set them. */
    private List<List<String>> rowTexts = List.of();
    private List<Role> rowInks = List.of();
    /** Each realized cell's text, by place and column, to change in place as the cursor moves. */
    private final Map<Integer, Node[]> rowNodes = new HashMap<>();
    /** The topics the path of the event the cursor is on crossed, whose names the flows table marks. */
    private java.util.Set<String> crossed = java.util.Set.of();
    /** The topics the flows table holds, in the order last given. */
    private List<String> flowTopics = List.of();
    /** What each topic's cells say, by topic: what a row realized later is built with. */
    private final Map<String, List<String>> flowCells = new HashMap<>();
    /** Each realized cell's text, by topic and column, to change in place as the counts move. */
    private final Map<String, Node[]> flowNodes = new HashMap<>();

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;
        status = new StatusBar(gui).slot(Landmarks.STATUS_RUN, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_LANES, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_DRAWING, StatusBar.Side.RIGHT, "");
        gui.landmark(Landmarks.STATUS, status.node());
        gui.landmark(Landmarks.STATUS_RUN, status.slot(Landmarks.STATUS_RUN));
        gui.landmark(Landmarks.STATUS_LANES, status.slot(Landmarks.STATUS_LANES));
        gui.landmark(Landmarks.STATUS_DRAWING, status.slot(Landmarks.STATUS_DRAWING));

        int count = Model.LANES.size();
        // Wider than the lanes by View.SLIDE_LANES lanes each side, or more for a long glide (reach): a picture is
        // clipped to its own node's box where the node is drawn, so a strip only as wide as the lanes left the edge it
        // slid away from empty for a whole glide.
        strip = gui.box().width(Length.percent(100f * (1 + 2 * View.SLIDE_LANES))).height(Length.FILL);
        // Clipped but never scrolled: the strip overflows it on purpose, and overflow alone would give it a scrollbar.
        lane = gui.box().width(Length.grow(1f)).height(Length.rem(LANE_REM * count)).clip(true).scroll(false, false)
                .background(gui.theme().color(Role.PANEL))
                .children(strip);
        gui.landmark(Landmarks.LANES, lane);
        names = new Node[count];
        for (int i = 0; i < count; i++) {
            names[i] = gui.text(Model.LANES.get(i)).width(NAMES).height(Length.rem(LANE_REM))
                    .textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        }
        Node column = gui.column().width(NAMES).children(names);
        follow = new IconButton(gui, Icon.SKIP_NEXT, "Follow the present").name(Landmarks.FOLLOW)
                .tip("Follow the present (End)");
        canFollow(false);
        gui.landmark(Landmarks.FOLLOW, follow.node());
        // Laid out, not translated, into the focused lane's row: a translated node is still hit where it was.
        above = gui.box().height(Length.rem(0f));
        Node followSlot = gui.row().height(Length.rem(LANE_REM)).alignItems(AlignItems.CENTER)
                .children(follow.node());
        Node ends = gui.column().width(Length.AUTO).height(Length.rem(LANE_REM * count)).children(above, followSlot);
        Node lanes = gui.row().width(Length.FILL).gap(Type.TIGHT).children(column, lane, ends);

        readouts = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        gui.landmark(Landmarks.READOUTS, readouts);
        Node readoutRow = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(readouts);

        details = new Node[View.DETAILS.size()];
        detailRows = new Node[details.length];
        for (int i = 0; i < details.length; i++) {
            Node label = gui.text(View.DETAILS.get(i)).width(NAMES).textColor(gui.theme().color(Role.DIM))
                    .textSize(Type.SMALL);
            details[i] = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.INK));
            gui.landmark(Landmarks.detail(View.DETAILS.get(i)), details[i]);
            detailRows[i] = gui.row().width(Length.FILL).gap(Type.TIGHT).children(label, details[i]);
        }
        Node detailsPanel = gui.column().width(Length.grow(1f)).height(Length.FILL).padding(Type.TIGHT)
                .clip(true).scroll(false, true).children(detailRows);
        gui.landmark(Landmarks.DETAILS, detailsPanel);

        List<Table.Column<Integer>> rowColumns = new ArrayList<>();
        for (int i = 0; i < View.ROW_COLUMNS.size(); i++) {
            int at = i;
            rowColumns.add(Table.Column.of(View.ROW_COLUMNS.get(i), ROW_WIDTHS.get(i), (nodes, place) -> {
                Node text = nodes.text(place < rowTexts.size() ? rowTexts.get(place).get(at) : "")
                        .textSize(Type.SMALL).textColor(gui.theme().color(inkOf(place)));
                rowNodes.computeIfAbsent(place, p -> new Node[View.ROW_COLUMNS.size()])[at] = text;
                return text;
            }));
        }
        rows = new Table<>(gui, ROW_REM, rowColumns).name(Landmarks.ROWS);
        rows.headers(Type.UI, Type.SMALL, Role.DIM);
        rows.node().width(Length.grow(2f)).height(Length.FILL);
        rows.items(java.util.stream.IntStream.range(0, Bench.AROUND).boxed().toList());
        gui.landmark(Landmarks.ROWS, rows.node());
        // The rows beside the details: the cursor's moment as the rows said it, and as the event it is on.
        Node cursorPanels = gui.row().width(Length.FILL).height(Length.rem(DETAILS_REM)).gap(Type.TIGHT)
                .children(detailsPanel, rows.node());

        warningText = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.WARNING_TEXT, warningText);
        warning = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER).padding(Type.TIGHT)
                .background(gui.theme().color(Role.PANEL)).border(Length.dp(1), gui.theme().color(Role.CAUTION))
                .children(IconView.of(gui, Icon.CAUTION).size(Length.rem(1.25f)).tint(Role.CAUTION).node(),
                        warningText);
        warning.visible(false);
        gui.landmark(Landmarks.WARNING, warning);

        runText = gui.text("").width(Length.grow(1f)).textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.RUN_TEXT, runText);
        runs = new Select<Run>(gui, Ui::runLabel).name(Landmarks.RUNS).placeholder("Choose an application");
        runs.node().width(Length.rem(18f));
        gui.focusable(runs.node(), false);
        gui.landmark(Landmarks.RUNS, runs.node());
        detach = new Button(gui, "Detach").name(Landmarks.DETACH).enabled(false);
        gui.focusable(detach.node(), false);
        gui.landmark(Landmarks.DETACH, detach.node());
        Node runPanel = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(runText, runs.node(), detach.node());
        gui.landmark(Landmarks.RUN, runPanel);

        List<Table.Column<String>> columns = new ArrayList<>();
        for (int i = 0; i < View.FLOW_COLUMNS.size(); i++) {
            int at = i;
            columns.add(Table.Column.of(View.FLOW_COLUMNS.get(i), FLOW_WIDTHS.get(i), (nodes, topic) -> {
                Node text = nodes.text(flowCells.getOrDefault(topic, View.NO_FLOW).get(at))
                        .textSize(Type.SMALL).textColor(gui.theme().color(at > 0 ? Role.DIM
                                : crossed.contains(topic) ? PATH : Role.INK));
                flowNodes.computeIfAbsent(topic, t -> new Node[View.FLOW_COLUMNS.size()])[at] = text;
                return text;
            }));
        }
        flows = new Table<>(gui, FLOW_ROW_REM, columns).name(Landmarks.FLOWS);
        flows.headers(Type.UI, Type.SMALL, Role.DIM);
        flows.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.FLOWS, flows.node());
        flowsWhy = gui.text("").width(Length.FILL).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        flowsWhy.visible(false);
        gui.landmark(Landmarks.FLOWS_WHY, flowsWhy);
        Node flowsPanel = gui.column().width(Length.grow(1.4f)).height(Length.FILL).gap(Type.TIGHT)
                .children(flowsWhy, flows.node());
        // The graph beside its flows: the same topics, drawn between the parts, the counts in the table.
        graph = gui.box().width(Length.grow(1f)).height(Length.FILL).clip(true).scroll(false, false)
                .background(gui.theme().color(Role.PANEL));
        gui.landmark(Landmarks.GRAPH, graph);
        Node panels = gui.row().width(Length.FILL).height(Length.grow(1f)).gap(Type.TIGHT)
                .children(graph, flowsPanel);

        Node timeline = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(Type.TIGHT).gap(Type.TIGHT)
                .children(runPanel, warning, lanes, cursorPanels, readoutRow, panels);
        gui.landmark(Landmarks.TIMELINE, timeline);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), timeline, status.node());
        focus(Model.MAIN);
    }

    /** The lanes' box: what the view measures to draw the strip for, a band a lane. */
    Node lane() {
        return lane;
    }

    /** The graph panel's box: what the view measures to draw the graph for, and draws it in. */
    Node graph() {
        return graph;
    }

    /** The lane the follow button stands at the right of, as {@link #names} last set it. */
    private int followLane = 0;

    /** Show {@code name} as the lane the arrows step through. */
    void focus(String name) {
        names(name, List.of());
    }

    /**
     * Show {@code focus} as the lane the arrows step through, and the lanes in {@code unrecorded} as ones the run's
     * probe does not record, faintest of all, so an empty band there reads as not looked at rather than quiet.
     */
    void names(String focus, List<String> unrecorded) {
        int index = Math.max(0, Model.LANES.indexOf(focus));
        if (index != followLane) {
            followLane = index;
            above.height(Length.rem(LANE_REM * index));
        }
        for (int i = 0; i < names.length; i++) {
            String name = Model.LANES.get(i);
            names[i].textColor(gui.theme().color(unrecorded.contains(name) ? Role.FAINT
                    : name.equals(focus) ? Role.INK : Role.DIM));
        }
    }

    /** What the status line's lanes slot last said. */
    private String lanesNote = "";

    /** What the status line says of the lanes the run does not record; empty while it records them all. */
    void lanesNote(String note) {
        if (!note.equals(lanesNote)) {
            lanesNote = note;
            status.text(Landmarks.STATUS_LANES, note);
        }
    }

    /**
     * What the details panel says, a value for each of {@code View.DETAILS} in its order: a row whose value is empty is
     * hidden, since the event at the cursor has no such field, and the clock's row always shows.
     */
    void details(List<String> values) {
        for (int i = 0; i < details.length; i++) {
            details[i].text(values.get(i));
            detailRows[i].visible(i == 0 || !values.get(i).isEmpty());
        }
    }

    /**
     * What the flows panel says: {@code why} there is no graph, when there is none, and a row a topic, each cell's text
     * as {@code cells} gives it by topic. The rows are rebuilt only when the topics change, which is once a run; the
     * counts, which change with every batch, change in place.
     */
    void flows(String why, List<String> topics, Map<String, List<String>> cells) {
        flowsWhy.text(why);
        flowsWhy.visible(!why.isEmpty());
        if (!topics.equals(flowTopics)) {
            flowTopics = List.copyOf(topics);
            flowCells.clear();
            flowCells.putAll(cells);
            flowNodes.clear();
            flows.items(flowTopics);
            return;
        }
        for (String topic : topics) {
            List<String> now = cells.get(topic);
            List<String> was = flowCells.put(topic, now);
            Node[] shown = flowNodes.get(topic);
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

    /**
     * Mark the flows table's {@code topics}, those the path of the event the cursor is on crossed, by their names in
     * the path's colour, and unmark the rest; in place, as the counts change.
     */
    void crossed(List<String> topics) {
        java.util.Set<String> now = java.util.Set.copyOf(topics);
        for (Map.Entry<String, Node[]> e : flowNodes.entrySet()) {
            boolean was = crossed.contains(e.getKey());
            boolean is = now.contains(e.getKey());
            if (was != is && e.getValue()[0] != null) {
                e.getValue()[0].textColor(gui.theme().color(is ? PATH : Role.INK));
            }
        }
        crossed = now;
    }

    /** The topics {@link #crossed} marked last, for a test. */
    java.util.Set<String> crossed() {
        return crossed;
    }

    /** What a topic's cells say in the flows panel, for a test: empty when it has no row. */
    List<String> flowCells(String topic) {
        return flowCells.getOrDefault(topic, List.of());
    }

    /**
     * What the rows panel says: a place a row, each cell's text as {@code texts} gives it and its ink as {@code inks}
     * does; a place past them is blank. In place, since the places never change and their rows do with every step.
     */
    void rows(List<List<String>> texts, List<Role> inks) {
        List<List<String>> was = rowTexts;
        List<Role> wasInks = rowInks;
        rowTexts = List.copyOf(texts);
        rowInks = List.copyOf(inks);
        for (Map.Entry<Integer, Node[]> e : rowNodes.entrySet()) {
            int place = e.getKey();
            boolean recolour = !inkOf(place, wasInks).equals(inkOf(place));
            for (int i = 0; i < e.getValue().length; i++) {
                Node shown = e.getValue()[i];
                if (shown == null) {
                    continue;
                }
                String now = cell(rowTexts, place, i);
                if (!now.equals(cell(was, place, i))) {
                    shown.text(now);
                }
                if (recolour) {
                    shown.textColor(gui.theme().color(inkOf(place)));
                }
            }
        }
    }

    /** What the rows panel's {@code place} last said, a cell a column, for a test: empty when it is blank. */
    List<String> rowCells(int place) {
        return place < rowTexts.size() ? rowTexts.get(place) : List.of();
    }

    /** The ink the rows panel's {@code place} is in, for a test. */
    Role rowInk(int place) {
        return inkOf(place);
    }

    private Role inkOf(int place) {
        return inkOf(place, rowInks);
    }

    private static Role inkOf(int place, List<Role> inks) {
        return place < inks.size() ? inks.get(place) : Role.INK;
    }

    private static String cell(List<List<String>> texts, int place, int column) {
        return place < texts.size() ? texts.get(place).get(column) : "";
    }

    /** What the readouts line says. */
    void readouts(String line) {
        readouts.text(line);
    }

    /** Whether the follow button can be pressed: while the cursor is stopped. */
    void canFollow(boolean can) {
        follow.enabled(can);
        // Never focused: a focused button claims Space, even disabled, and Space pauses here; End is its key (FN-11).
        gui.focusable(follow.node(), false);
    }

    /** The address the follow button presses with. */
    String followAddress() {
        return follow.address();
    }

    /** How many lanes the strip reaches past the lane each side, as {@link #reach} last set it. */
    private int reach = View.SLIDE_LANES;

    /** Reach {@code lanes} lanes past the lane each side, so a glide that far never slides past the strip's end. */
    void reach(int lanes) {
        if (lanes != reach) {
            reach = lanes;
            strip.width(Length.percent(100f * (1 + 2 * lanes)));
        }
    }

    /** The strip in the lane: what the view draws its marks on, and slides. */
    Node strip() {
        return strip;
    }

    /** What the status line's drawing slot last said. */
    private String drawing = "";

    /** What the status line says the lanes could not draw as they should; empty while they can. */
    void drawing(String note) {
        if (!note.equals(drawing)) {
            drawing = note;
            status.text(Landmarks.STATUS_DRAWING, note);
        }
    }

    /** What the warning banner last said. */
    private String warned = "";

    /** What the banner over the lanes warns of, in plain sight; empty hides it. */
    void warning(String text) {
        if (!text.equals(warned)) {
            warned = text;
            warningText.text(text);
            warning.visible(!text.isEmpty());
        }
    }

    /** What the run panel last showed: its line, and the picker's options and value. */
    private String runSaid = "";
    private List<Run> runChoices = List.of();
    private Run runShown;

    /**
     * What the run panel shows: {@code line}, what is inspected and from where; the running applications to choose
     * from, {@code current} shown chosen when it is among them; and whether there is a run to detach from.
     */
    void runPanel(String line, List<Run> choices, Run current, boolean canDetach) {
        if (!line.equals(runSaid)) {
            runSaid = line;
            runText.text(line);
        }
        if (!choices.equals(runChoices)) {
            runChoices = choices;
            runs.options(choices);
            runShown = null;
        }
        Run shown = current != null && choices.contains(current) ? current : null;
        if (!Objects.equals(shown, runShown)) {
            runShown = shown;
            runs.show(shown);
        }
        detach.enabled(canDetach);
    }

    /** What the run panel's line last said: what is inspected and from where. */
    String runLine() {
        return runSaid;
    }

    /** The address the picker commits a choice with. */
    String runsAddress() {
        return runs.address();
    }

    /** The address the detach button presses with. */
    String detachAddress() {
        return detach.address();
    }

    /** A running application as the picker lists it. */
    private static String runLabel(Run run) {
        return (run.title().isBlank() ? run.name() : run.title()) + " · pid " + run.pid();
    }

    /** What the status line says: which run is inspected, or why none is. */
    void status(String message) {
        status.text(Landmarks.STATUS_RUN, message);
    }
}
