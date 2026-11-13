package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Bench;
import dev.sibarum.inspector.core.ClockLedger;
import dev.sibarum.inspector.core.Csv;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Model;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.Table;
import dev.vexelray.gui.widget.TitleBar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The window: the framework's title bar, the timeline, and a status line under both.
 *
 * <h2>Cards</h2>
 *
 * <p>Every section is a card ({@link #card}), as the redesign of 2026-11-10 draws them: a panel off the page, rounded,
 * edged in {@link Look#EDGE}, its name in faint capitals over it. In order: Timeline; Cursor (the details) beside
 * Events (the rows panel); Clocks (the threads panel and the readouts); Run again (the replay panel, once pressed);
 * and Graph beside Topics (the flows table).
 *
 * <h2>One scrollbar</h2>
 *
 * <p>The page scrolls, and nothing on it does: each card is as tall as what it holds, so a small window reaches the
 * graph by scrolling the page. A card whose contents could outgrow it shows what fits, with no scrollbar of its own,
 * and a pop-out button at its heading's right opens all of it in a window of its own, as the graph's does: the
 * details in the cursor window, the topics past the first {@link #TOPICS_SHOWN} in the topics window, the differences
 * past the first {@link #REPLAY_SHOWN} in the replay window ({@link PanelWindow}). The Events and Clocks tables have
 * a fixed count of places, and are as tall as all of them.
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
 * <p>In the title bar, after the mark: the picker of running applications, which names the run; Run again and Detach;
 * then how the run was reached and where its logic runs ({@link #runPanel}). No control takes the focus: a focused
 * control claims Space, and Space pauses here (FN-11), so all are driven by the pointer. Export frame is on the Cursor
 * card, as it saves the frame at the cursor.
 *
 * <h2>The warning</h2>
 *
 * <p>In the title bar, a brass chip with the caution mark, shown only while what the lanes show may be less than
 * whole: a run of another link version, which may not send all the Inspector reads ({@link #warning}).
 *
 * <h2>The details</h2>
 *
 * <p>Right under the lanes, where the eye goes first: a label and a value a line ({@link #details}), what the run's
 * clock said at the cursor and the fields of the event it is on, a row for each field of the event schema, shown only
 * while that event has it. As tall as the Events card beside it, and what does not fit is clipped: a click's path is
 * thirty hops, read whole in the cursor window.
 *
 * <h2>The rows panel</h2>
 *
 * <p>Beside the details, as tall: the raw rows around the cursor, a place a row, the cursor's in the accent and those
 * on the path of the event it is on in the path's colour ({@link #rows}). The places stay and their text changes, so a
 * step rebuilds nothing.
 *
 * <h2>The threads panel</h2>
 *
 * <p>Under the details and the rows, the full width: a place a clock, the turn of it the cursor is in, cut into
 * working, waiting, resting and unaccounted, with recording's share and the wakes ({@link #threads}). Changed in
 * place, as the rows panel is.
 *
 * <h2>The replay panel</h2>
 *
 * <p>Over the graph and flows, once Run again has been pressed: what running the run again found, in words, and a place
 * for each send that differs, with its tick, which a click goes to ({@link #replay}). Hidden until then, so a run
 * never replayed shows nothing of it. Changed in place, as the rows panel is.
 *
 * <h2>The readouts</h2>
 *
 * <p>One line of numbers under the timeline, over the second before the cursor ({@link #readouts}), and at its right
 * end a button each for a selected stretch's longest pass, tick and draw ({@link #longest}).
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
    static final Role PATH = Look.PATH;

    /** How many topics the Topics card shows, without a scrollbar: the rest are in the topics window. */
    static final int TOPICS_SHOWN = 10;

    /** A row of the rows panel, in rem: eleven fit beside the details with its header. */
    private static final float ROW_REM = 1.25f;

    /** The rows panel's column widths, in {@link View#ROW_COLUMNS}' order: the detail takes what is left. */
    private static final List<Length> ROW_WIDTHS = List.of(Length.rem(7.5f), Length.rem(8f), Length.rem(5f),
            Length.rem(8f), Length.grow(1f), Length.rem(9f));

    /** The threads panel's column widths, in {@link View#THREAD_COLUMNS}' order: the note takes what is left. */
    private static final List<Length> THREAD_WIDTHS = List.of(Length.rem(7f), Length.rem(6.5f), Length.rem(5.5f),
            Length.rem(7f), Length.rem(7f), Length.rem(7f), Length.rem(7f), Length.rem(7f), Length.rem(5f),
            Length.rem(7.5f), Length.grow(1f));

    /** The replay panel's column widths, in {@link View#REPLAY_COLUMNS}' order: the two sends share what is left. */
    static final List<Length> REPLAY_WIDTHS = List.of(Length.rem(5f), Length.rem(4.5f), Length.rem(18f),
            Length.grow(1f), Length.grow(1f));

    /** How many places the Run again card shows, without a scrollbar: every difference is in the replay window. */
    static final int REPLAY_SHOWN = 5;

    /** The replay panel's ink, by column: the two sends in the ink, the tick, send and topic dimmed. */
    static Role replayInk(int column) {
        return column >= View.REPLAY_COLUMNS.size() - 2 ? Role.INK : Role.DIM;
    }

    /**
     * Copy, on every table's row menu: chosen, it says so on {@code ListView.CHOSEN}, and the view copies that table's
     * selection ({@link #copied}). Its line shows the chord the keymap's Copy has now.
     */
    static final MenuAction COPY = MenuAction.of(Landmarks.COPY, Icon.COPY, "Copy").command(Landmarks.COPY);

    private final Gui gui;
    private final StatusBar status;
    private final Node lane;
    private final Node strip;
    private final Node[] names;
    private final Node readouts;
    /** The buttons that go to the stretch's longest pass, tick and draw, in {@link View#LONGEST}'s order. */
    private final Button[] longest;
    private final IconButton follow;
    /** The title bar's pause button, as every window has one. */
    private final PauseButton pause;
    /** The space over the follow button, a lane high for each lane above the focused one. */
    private final Node above;
    private final DetailsList details;
    private final Node warning;
    private final Node warningText;
    private final Node runText;
    private final Select<Run> runs;
    private final Button detach;
    private final Button runAgain;
    private final Button exportFrame;
    /** Whether Export frame is enabled, as {@link #exportable} last set it. */
    private boolean exportable;
    private final Node replay;
    private final Node replayText;
    private final Places replayTable;
    private String replaySaid = "";
    private final Topics flows;
    private final Node flowsWhy;
    /** Beside the Topics card's name: how many of the topics it shows, while it cannot show them all. */
    private final Node flowsShown;
    private String flowsShownSaid = "";
    private final Node graph;
    private final IconButton openGraph;
    private final IconButton openCursor;
    private final IconButton openTopics;
    private final IconButton openReplay;
    private final Table<Integer> rows;
    /** What each of the rows panel's places says, a cell a column, and in what ink, as {@link #rows} last set them. */
    private List<List<String>> rowTexts = List.of();
    private List<Role> rowInks = List.of();
    /** Each realized cell's text, by place and column, to change in place as the cursor moves. */
    private final Map<Integer, Node[]> rowNodes = new HashMap<>();
    private final Table<Integer> threads;
    /** What each of the threads panel's places says, a cell a column, as {@link #threads} last set them. */
    private List<List<String>> threadTexts = List.of();
    /** Each realized cell's text, by place and column, to change in place as the cursor moves. */
    private final Map<Integer, Node[]> threadNodes = new HashMap<>();

    Ui(Gui gui, TitleBar titleBar) {
        this.gui = gui;
        // The name as the bar's wordmark, after the mark in its lockup: the bar inks its letters as the pointer comes
        // and goes, and the gear keeps its lime.
        TitleBar.Wordmark wordmark = Wordmark.bar(gui);
        titleBar.wordmark(wordmark);
        gui.landmark(Landmarks.WORDMARK, wordmark.node());
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
        longest = new Button[View.LONGEST.size()];
        Node[] readoutNodes = new Node[1 + longest.length];
        readoutNodes[0] = readouts;
        for (int i = 0; i < longest.length; i++) {
            String landmark = LONGEST_LANDMARKS.get(i);
            longest[i] = new Button(gui, View.longestLabel(i, -1)).kind(Button.Kind.GHOST).name(landmark)
                    .enabled(false);
            // Never focused, as the follow button is: a focused button claims Space, and Space pauses here (FN-11).
            gui.focusable(longest[i].node(), false);
            gui.landmark(landmark, longest[i].node());
            readoutNodes[1 + i] = longest[i].node();
        }
        Node readoutRow = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(readoutNodes);

        // Clipped to the card, never scrolled: the dashboard has one scrollbar, its own, and a long path is read whole
        // in the cursor window.
        details = new DetailsList(gui, false, true);
        Node detailsPanel = details.node();
        gui.landmark(Landmarks.DETAILS, detailsPanel);
        openCursor = popOut(Landmarks.CURSOR_OPEN, "Open the cursor's details in their own window");

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
        rows.rows().name(Landmarks.ROWS_BODY).action(COPY);
        // Never focused: a focused list claims Space and the arrows, which pause and step here (FN-11).
        gui.focusable(rows.rows().node(), false);
        rows.headers(Type.UI, Type.SMALL, Role.DIM);
        // Tall enough for every place, so it never scrolls.
        rows.node().width(Length.FILL).height(Places.fit(Bench.AROUND, ROW_REM));
        rows.items(java.util.stream.IntStream.range(0, Bench.AROUND).boxed().toList());
        gui.landmark(Landmarks.ROWS, rows.node());
        // On the cursor's card, as what it saves is the frame at the cursor.
        exportFrame = new Button(gui, "Export frame").kind(Button.Kind.GHOST).name(Landmarks.EXPORT).enabled(false);
        // Never focused, as the follow button is not (FN-11).
        gui.focusable(exportFrame.node(), false);
        gui.landmark(Landmarks.EXPORT, exportFrame.node());
        // The rows beside the details: the cursor's moment as the rows said it, and as the event it is on. The Events
        // card is as tall as its rows, and the Cursor card is stretched to it.
        Node cursorPanels = gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.STRETCH)
                .children(card(head("Cursor", exportFrame.node(), openCursor.node()), detailsPanel)
                                .width(Length.grow(1f)),
                        card("Events", rows.node()).width(Length.grow(2f)));

        List<Table.Column<Integer>> threadColumns = new ArrayList<>();
        for (int i = 0; i < View.THREAD_COLUMNS.size(); i++) {
            int at = i;
            threadColumns.add(Table.Column.of(View.THREAD_COLUMNS.get(i), THREAD_WIDTHS.get(i), (nodes, place) -> {
                Node text = nodes.text(cell(threadTexts, place, at)).textSize(Type.SMALL)
                        .textColor(gui.theme().color(at == View.THREAD_COLUMNS.size() - 1 ? Role.DIM : Role.INK));
                threadNodes.computeIfAbsent(place, p -> new Node[View.THREAD_COLUMNS.size()])[at] = text;
                return text;
            }));
        }
        threads = new Table<>(gui, ROW_REM, threadColumns).name(Landmarks.THREADS);
        threads.rows().name(Landmarks.THREADS_BODY).action(COPY);
        // Never focused, as the rows panel is not (FN-11).
        gui.focusable(threads.rows().node(), false);
        threads.headers(Type.UI, Type.SMALL, Role.DIM);
        threads.node().width(Length.FILL).height(Length.rem(ROW_REM * (ClockLedger.CLOCKS.size() + 1) + 0.75f));
        threads.items(java.util.stream.IntStream.range(0, ClockLedger.CLOCKS.size()).boxed().toList());
        gui.landmark(Landmarks.THREADS, threads.node());

        warningText = gui.text("").width(Length.AUTO).textSize(Type.SMALL).wordWrap(false)
                .textColor(gui.theme().color(Role.CAUTION));
        gui.landmark(Landmarks.WARNING_TEXT, warningText);
        // A chip in the title bar: brass on a wash of it, as the design draws a warning.
        warning = gui.row().width(Length.AUTO).height(Length.rem(1.75f)).gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER).padding(Length.ZERO, Type.PAD).corner(Type.CHIP_CORNER)
                .background(Color.withAlpha(gui.theme().color(Role.CAUTION), 0.06f))
                .border(Length.dp(1), Color.withAlpha(gui.theme().color(Role.CAUTION), 0.35f))
                .children(IconView.of(gui, Icon.CAUTION).size(Length.rem(1f)).tint(Role.CAUTION).node(),
                        warningText);
        warning.visible(false);
        gui.landmark(Landmarks.WARNING, warning);

        runText = gui.text("").width(Length.AUTO).textSize(Type.SMALL).textColor(gui.theme().color(Role.DIM))
                .wordWrap(false);
        gui.landmark(Landmarks.RUN_TEXT, runText);
        runs = new Select<Run>(gui, Ui::runLabel).name(Landmarks.RUNS).placeholder("Choose an application");
        runs.node().width(Length.rem(14f)).windowRegion(WindowRegion.INTERACTIVE);
        gui.focusable(runs.node(), false);
        gui.landmark(Landmarks.RUNS, runs.node());
        detach = barButton("Detach", Landmarks.DETACH);
        runAgain = barButton("Run again", Landmarks.RUN_AGAIN);
        pause = new PauseButton(gui);
        // In the title bar, after the mark: the pause button every window wears, the run, what can be done to it, the
        // warning when there is one, and how it was reached and where its logic runs. The bar's left gives way at its
        // end when the window is narrow (it clips, and an application's part of it can take none of the caption's
        // slack), so the words go last and the buttons are never what is cut.
        Node runPanel = gui.row().width(Length.AUTO).height(Length.FILL).gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER).padding(Length.ZERO, Type.TIGHT)
                .children(pause.node(), runs.node(), runAgain.node(), detach.node(), warning, runText);
        gui.landmark(Landmarks.RUN, runPanel);
        // The bar's own title would only say the mark's name again.
        titleBar.title("");
        titleBar.addLeading(runPanel);

        // The first topics, as many as fit without a scrollbar; every one is in the topics window.
        flows = new Topics(gui, Landmarks.FLOWS, Landmarks.FLOWS_BODY, TOPICS_SHOWN);
        flows.node().width(Length.FILL).height(Places.fit(TOPICS_SHOWN, Topics.ROW_REM));
        flowsWhy = gui.text("").width(Length.FILL).textColor(gui.theme().color(Role.DIM)).textSize(Type.SMALL);
        flowsWhy.visible(false);
        gui.landmark(Landmarks.FLOWS_WHY, flowsWhy);
        flowsShown = gui.text("").width(Length.AUTO).textColor(gui.theme().color(Role.FAINT)).textSize(Type.SMALL)
                .wordWrap(false);
        openTopics = popOut(Landmarks.TOPICS_OPEN, "Open every topic in its own window");
        Node flowsPanel = card(head("Topics", flowsShown, openTopics.node()), flowsWhy, flows.node())
                .width(Length.grow(1.4f));
        // The graph beside its flows: the same topics, drawn between the parts, the counts in the table. A well in
        // its card, a step under it, as the design sinks the graph's ground; as tall as the Topics card is.
        graph = gui.box().width(Length.FILL).height(Length.grow(1f)).clip(true).scroll(false, false).childMotion(false)
                .corner(Type.CHIP_CORNER).background(gui.theme().color(Role.PAGE));
        gui.landmark(Landmarks.GRAPH, graph);
        // Over the graph, at its right: the button that gives it a window of its own to be looked around in.
        openGraph = new IconButton(gui, Icon.POP_OUT, "Open the graph in its own window").name(Landmarks.GRAPH_OPEN)
                .tip("Open the graph in its own window (G)");
        gui.landmark(Landmarks.GRAPH_OPEN, openGraph.node());
        Node graphPanel = card(head("Graph", openGraph.node()), graph).width(Length.grow(1f));
        Node panels = gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.STRETCH)
                .children(graphPanel, flowsPanel);

        replayText = gui.text("").width(Length.FILL).textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.REPLAY_TEXT, replayText);
        // The first differences, as many as fit without a scrollbar; every one is in the replay window.
        replayTable = new Places(gui, Landmarks.REPLAY, Landmarks.REPLAY_BODY, View.REPLAY_COLUMNS, REPLAY_WIDTHS,
                ROW_REM, REPLAY_SHOWN, Ui::replayInk);
        replayTable.node().width(Length.FILL).height(Places.fit(REPLAY_SHOWN, ROW_REM));
        replayTable.node().visible(false);
        openReplay = popOut(Landmarks.REPLAY_OPEN, "Open every difference in its own window");
        replay = card(head("Run again", openReplay.node()), replayText, replayTable.node()).width(Length.FILL);
        replay.visible(false);
        gui.landmark(Landmarks.REPLAY, replay);

        // The cards in the design's order: the timeline, the cursor and its rows, the clocks, then what Run again
        // found (once it has run), and the graph beside its topics. Each as tall as what it holds, and the page,
        // not a card, scrolls: the dashboard has one scrollbar, and whatever would scroll in a card is in a window.
        Node page = gui.column().width(Length.FILL).padding(Type.GAP).gap(Type.GAP)
                .children(card("Timeline", lanes).width(Length.FILL), cursorPanels,
                        card("Clocks", threads.node(), readoutRow).width(Length.FILL), replay, panels);
        Node timeline = gui.column().width(Length.FILL).height(Length.grow(1f)).clip(true).scroll(false, true)
                .children(page);
        gui.landmark(Landmarks.TIMELINE, timeline);

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), timeline, status.node());
        focus(Model.MAIN);
    }

    /**
     * A card, as the design draws every section: a panel standing off the page, rounded, edged a step brighter than
     * the ladder's line, with its name in small faint capitals over {@code body}.
     */
    private Node card(String title, Node... body) {
        return card(head(title), body);
    }

    /** A card under {@code head}, a heading {@link #head} made. */
    private Node card(Node head, Node... body) {
        Node[] children = new Node[1 + body.length];
        children[0] = head;
        System.arraycopy(body, 0, children, 1, body.length);
        return gui.column().gap(Type.TIGHT).padding(Type.PAD).corner(Type.CARD_CORNER)
                .background(gui.theme().color(Role.PANEL)).border(Length.dp(1), gui.theme().color(Look.EDGE))
                .children(children);
    }

    /**
     * A card's heading: its name in capitals, faint, and at its right {@code tools}. The design spaces its letters
     * wide, which no text node can (framework-notes FN-21), so the capitals carry it alone.
     */
    private Node head(String title, Node... tools) {
        Node[] children = new Node[1 + tools.length];
        children[0] = gui.text(title.toUpperCase(java.util.Locale.ROOT)).width(Length.grow(1f))
                .textColor(gui.theme().color(Role.FAINT)).textSize(Type.SMALL);
        System.arraycopy(tools, 0, children, 1, tools.length);
        return gui.row().width(Length.FILL).alignItems(AlignItems.CENTER).gap(Type.TIGHT).children(children);
    }

    /**
     * A button in the title bar: quiet until the pointer is on it, never focused (a focused button claims Space, and
     * Space pauses here, FN-11), and a click of its own rather than the bar's drag.
     */
    private Button barButton(String label, String landmark) {
        Button b = new Button(gui, label).kind(Button.Kind.GHOST).name(landmark).enabled(false);
        b.node().windowRegion(WindowRegion.INTERACTIVE);
        gui.focusable(b.node(), false);
        gui.landmark(landmark, b.node());
        return b;
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
        details.set(values);
    }

    /**
     * What the flows panel says: {@code why} there is no graph, when there is none, and a row a topic, each cell's text
     * as {@code cells} gives it by topic, the first {@link #TOPICS_SHOWN} of them, and beside its name how many of how
     * many it shows while that is not all.
     */
    void flows(String why, List<String> topics, Map<String, List<String>> cells) {
        flowsWhy.text(why);
        flowsWhy.visible(!why.isEmpty());
        boolean grew = topics.size() != flows.count();
        flows.set(topics, cells);
        if (grew) {
            flowsShownSaid = shownOf(TOPICS_SHOWN, topics.size());
            flowsShown.text(flowsShownSaid);
        }
    }

    /** What a card that shows the first {@code shown} of {@code of} says beside its name: empty while that is all. */
    static String shownOf(int shown, int of) {
        return of > shown ? "first " + shown + " of " + of : "";
    }

    /**
     * Mark the flows table's {@code topics}, those the path of the event the cursor is on crossed, by their names in
     * the path's colour, and unmark the rest; in place, as the counts change.
     */
    void crossed(List<String> topics) {
        flows.crossed(topics);
    }

    /** The topics {@link #crossed} marked last, for a test. */
    java.util.Set<String> crossed() {
        return flows.crossed();
    }

    /** What a topic's cells say in the flows panel, for a test: empty when it has no row. */
    List<String> flowCells(String topic) {
        return flows.cells(topic);
    }

    /** What the Topics card says beside its name, for a test. */
    String flowsShown() {
        return flowsShownSaid;
    }

    /**
     * What the rows panel says: a place a row, each cell's text as {@code texts} gives it and its ink as {@code inks}
     * does; a place past them is blank. In place, since the places never change and their rows do with every step.
     */
    void rows(List<List<String>> texts, List<Role> inks, int selected) {
        // The table's own selection follows the cursor's row, so a click that selected a place does not stay there
        // when the rows move under it. Rows chosen with Shift or Ctrl, to copy, stay chosen while the rows under them
        // do not move.
        if (selected >= 0 && (!texts.equals(rowTexts) || rows.selection().size() <= 1)) {
            rows.selection().at(selected);
        }
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

    /**
     * What the threads panel says: a place a clock, each cell's text as {@code texts} gives it; a place past them is
     * blank. In place, as the rows panel's.
     */
    void threads(List<List<String>> texts) {
        List<List<String>> was = threadTexts;
        threadTexts = List.copyOf(texts);
        for (Map.Entry<Integer, Node[]> e : threadNodes.entrySet()) {
            for (int i = 0; i < e.getValue().length; i++) {
                String now = cell(threadTexts, e.getKey(), i);
                if (e.getValue()[i] != null && !now.equals(cell(was, e.getKey(), i))) {
                    e.getValue()[i].text(now);
                }
            }
        }
    }

    /**
     * What the replay panel says: {@code said}, what running again found, and a place for each difference, each cell's
     * text as {@code texts} gives it; hidden while {@code said} is empty, and the table while nothing differs. In
     * place, as the rows panel's.
     */
    void replay(String said, List<List<String>> texts) {
        if (!said.equals(replaySaid)) {
            replaySaid = said;
            replayText.text(said);
            replay.visible(!said.isEmpty());
        }
        replayTable.node().visible(!texts.isEmpty());
        replayTable.set(texts);
    }

    /** What the replay panel's line last said; empty while it is hidden. */
    String replayLine() {
        return replaySaid;
    }

    /** What the replay panel's {@code place} last said, a cell a column, for a test: empty when it is blank. */
    List<String> replayCells(int place) {
        return replayTable.cells(place);
    }

    /** Where the replay panel's body is on the bus: what its clicks carry. */
    String replayAddress() {
        return replayTable.address();
    }

    /** Enable Export frame while there is something kept to export. */
    void exportable(boolean can) {
        if (can != exportable) {
            exportable = can;
            exportFrame.enabled(can);
        }
    }

    /** The address the Export frame button presses with. */
    String exportAddress() {
        return exportFrame.address();
    }

    /** The address the Run again button presses with. */
    String runAgainAddress() {
        return runAgain.address();
    }

    /** What the threads panel's {@code place} last said, a cell a column, for a test: empty when it is blank. */
    List<String> threadCells(int place) {
        return place < threadTexts.size() ? threadTexts.get(place) : List.of();
    }

    /**
     * The selected rows of the table whose body is at {@code list}, as CSV with the table's column titles first, in
     * the order the table shows them; empty when {@code list} is no table's or nothing in it with text is selected.
     * Blank places (the rows and threads panels keep places past their rows) are left out.
     */
    Optional<String> copied(String list) {
        List<List<String>> chosen = new ArrayList<>();
        List<String> headers;
        if (list.equals(rows.rows().address())) {
            headers = View.ROW_COLUMNS;
            placed(rows, rowTexts, chosen);
        } else if (list.equals(threads.rows().address())) {
            headers = View.THREAD_COLUMNS;
            placed(threads, threadTexts, chosen);
        } else if (list.equals(replayTable.address())) {
            return replayTable.copied();
        } else if (list.equals(flows.address())) {
            return flows.copied();
        } else {
            return Optional.empty();
        }
        return chosen.isEmpty() ? Optional.empty() : Optional.of(Csv.of(headers, chosen));
    }

    /** Onto {@code chosen}, the texts of {@code table}'s selected places that have any, in order of place. */
    private static void placed(Table<Integer> table, List<List<String>> texts, List<List<String>> chosen) {
        java.util.Set<Integer> selected = table.selection().selection();
        for (int place = 0; place < texts.size(); place++) {
            if (selected.contains(place)) {
                chosen.add(texts.get(place));
            }
        }
    }

    /**
     * Whether the table whose body is at {@code list} has {@code item} alone selected: what a plain click leaves, and
     * a Shift- or Ctrl-click, which chooses rows to copy, does not.
     */
    boolean alone(String list, Object item) {
        for (Table<?> t : List.of(rows, threads, replayTable.table(), flows.table())) {
            if (list.equals(t.rows().address())) {
                java.util.Set<?> selected = t.selection().selection();
                return selected.size() == 1 && selected.contains(item);
            }
        }
        return false;
    }

    /** The rows panel's selection, for a test to choose rows in as Shift and Ctrl would. */
    dev.vexelray.gui.widget.SelectionModel<Integer> rowSelection() {
        return rows.selection();
    }

    /** Where the rows panel's body is on the bus: what its clicks carry. */
    String rowsAddress() {
        return rows.rows().address();
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

    /** The landmarks of the longest buttons, in {@link View#LONGEST}'s order. */
    private static final List<String> LONGEST_LANDMARKS = List.of(Landmarks.LONGEST_PASS, Landmarks.LONGEST_TICK,
            Landmarks.LONGEST_DRAW);

    /** The {@code which}th longest button's label, and whether it can be pressed. */
    void longest(int which, String label, boolean can) {
        longest[which].label(label).enabled(can);
        gui.focusable(longest[which].node(), false);
    }

    /** Which longest button presses with {@code address}, in {@link View#LONGEST}'s order; -1 for none of them. */
    int longestAt(String address) {
        for (int i = 0; i < longest.length; i++) {
            if (longest[i].address().equals(address)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the follow button can be pressed: while the cursor is stopped. */
    void canFollow(boolean can) {
        follow.enabled(can);
        // Never focused: a focused button claims Space, even disabled, and Space pauses here; End is its key (FN-11).
        gui.focusable(follow.node(), false);
    }

    /** The address the follow button presses with. */
    /** The graph panel's button that opens the graph window. */
    /**
     * The window a pop-out button pressed with {@code address} opens: the cursor's, the topics' or the replay's key;
     * empty for none of them. The graph's has its own ({@link #openGraphAddress}), as G presses it too.
     */
    Optional<String> popOutWindow(String address) {
        return address.equals(openCursor.address()) ? Optional.of(CursorWindow.KEY)
                : address.equals(openTopics.address()) ? Optional.of(TopicsWindow.KEY)
                : address.equals(openReplay.address()) ? Optional.of(ReplayWindow.KEY) : Optional.empty();
    }

    /** The address the pop-out button with {@code landmark} presses with, for a test. */
    String popOutAddress(String landmark) {
        return landmark.equals(Landmarks.CURSOR_OPEN) ? openCursor.address()
                : landmark.equals(Landmarks.TOPICS_OPEN) ? openTopics.address() : openReplay.address();
    }

    /** A card's pop-out button, at its heading's right: opens what the card shows in a window, with room to scroll. */
    private IconButton popOut(String landmark, String tip) {
        IconButton b = new IconButton(gui, Icon.POP_OUT, tip).name(landmark).tip(tip);
        gui.focusable(b.node(), false);
        gui.landmark(landmark, b.node());
        return b;
    }

    String openGraphAddress() {
        return openGraph.address();
    }

    /** The title bar's pause button. */
    PauseButton pause() {
        return pause;
    }

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
     * What the run panel shows: {@code line}, what is inspected and from where, of which the picker already names the
     * run, so beside it is only {@code beside}; the running applications to choose from, {@code current} shown chosen
     * when it is among them; and whether there is a run to detach from.
     */
    void runPanel(String line, String beside, List<Run> choices, Run current, boolean canDetach) {
        if (!line.equals(runSaid)) {
            runSaid = line;
            runText.text(beside);
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
        runAgain.enabled(canDetach);
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
