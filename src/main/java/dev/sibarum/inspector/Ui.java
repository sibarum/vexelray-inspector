package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.Beat;
import dev.sibarum.inspector.clock.Durations;
import dev.sibarum.inspector.clock.Escapement;
import dev.sibarum.inspector.tape.Tape;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar, the component graph, the escapement beside its readouts under it, the tape
 * under them, and a status line saying where the rows come from.
 *
 * <p>It is the listener of the {@link Escapement} that {@link Show} feeds on the show clock, so it is called on the
 * timeline with the whole new {@link Beat} each time. A prop written off the GUI thread is queued and applied by the next drain, which is the
 * framework's own idiom; nothing here reads the tree back.
 */
final class Ui implements Escapement.Listener {

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Movement movement;
    private final GraphView graph;
    private final TapeView tape;
    private final Node inspecting;
    private final Picker picker;
    private final Node history;
    private final Node[] historyLines;
    private final Node frame;
    private final Node frameTime;
    private final Node interval;
    private final Node park;
    private final Node wake;
    private final Node waker;
    private final Node lost;
    private final Node stalls;

    Ui(Gui gui, KronoGui krono, TitleBar titleBar, Picker picker, dev.sibarum.inspector.circuit.BusGraph declared,
       Tape rows) {
        this.titleBar = titleBar;
        this.movement = new Movement(gui, krono);
        this.graph = new GraphView(gui);
        this.tape = new TapeView(gui, rows);
        this.graph.graph(declared);
        this.picker = picker;

        // Two columns of four, so the panel is no taller than the movement, which sits under the graph. Each is
        // fixed and wide enough for its longest value on one line (#12345  (12345 seen), rested 12.3 ms of forever,
        // vexelray-caret-blink), so the panel keeps its shape while the values change under it.
        Node left = gui.column().width(Length.rem(14.5f)).gap(Length.rem(0.6f));
        Node right = gui.column().width(Length.rem(19.5f)).gap(Length.rem(0.6f));
        Node readouts = gui.column()
                .gap(Length.rem(0.6f))
                .padding(Length.rem(1.0f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f));
        Node heading = gui.text("THE MAIN LOOP").textSize(Length.rem(0.8f)).textColor(gui.theme().color(Role.ACCENT));
        readouts.append(heading);
        readouts.append(gui.row().gap(Length.rem(1.2f)).children(left, right));
        frame = readout(gui, left, "Frame", Landmarks.FRAME);
        frameTime = readout(gui, left, "Frame time", Landmarks.FRAME_TIME);
        interval = readout(gui, left, "Since the frame before", Landmarks.INTERVAL);
        stalls = readout(gui, left, "Stalls", Landmarks.STALLS);
        park = readout(gui, right, "Park", Landmarks.PARK);
        wake = readout(gui, right, "Last rest ended by", Landmarks.WAKE);
        waker = readout(gui, right, "On the thread", Landmarks.WAKER);
        lost = readout(gui, right, "Rows lost", Landmarks.LOST);

        // Two faces of one area: the movement while something is being inspected, the picker while nothing is. Shown
        // and hidden rather than swapped, so each keeps its state (and the picker its entries) between turns.
        // This application's builds, under the movement: each line one build, summed over the runs kept of it, the
        // running one marked. Hidden until there is a build to show.
        history = gui.column()
                .gap(Length.rem(0.2f))
                .padding(Length.rem(0.6f))
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.4f));
        history.append(gui.text("THIS APPLICATION'S BUILDS").textSize(Length.rem(0.7f))
                .textColor(gui.theme().color(Role.ACCENT)));
        historyLines = new Node[dev.sibarum.inspector.history.Runs.LIMIT];
        for (int i = 0; i < historyLines.length; i++) {
            historyLines[i] = gui.text("").font("mono").textSize(Length.rem(0.72f))
                    .textColor(gui.theme().color(Role.INK));
            historyLines[i].visible(false);
            gui.landmark(Landmarks.HISTORY + "." + (i + 1), historyLines[i]);
            history.append(historyLines[i]);
        }
        history.visible(false);
        gui.landmark(Landmarks.HISTORY, history);
        inspecting = gui.column()
                .alignItems(AlignItems.CENTER)
                .gap(Length.rem(0.8f))
                .children(graph.node(), gui.row()
                        .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                        .gap(Length.rem(2.5f))
                        .children(movement.node(), readouts), tape.node(), history);
        picker.node().visible(false);
        Node body = gui.row()
                .width(Length.FILL).height(Length.grow(1f))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .children(inspecting, picker.node());

        status = new StatusBar(gui).slot(Landmarks.STATUS_SOURCE, StatusBar.Side.LEFT, "");
        gui.landmark(Landmarks.STATUS, status.node());
        gui.landmark(Landmarks.STATUS_SOURCE, status.slot(Landmarks.STATUS_SOURCE));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body, status.node());
    }

    /** The component graph, which {@link Show} moves. */
    GraphView graph() {
        return graph;
    }

    /** The tape's strip, which {@link Show} moves. */
    TapeView tape() {
        return tape;
    }

    /** A name over a value, which never wraps: a longer one is cut at the column's edge; returns the value. */
    private static Node readout(Gui gui, Node into, String name, String landmark) {
        Node value = gui.text("—").font("mono").textSize(Length.rem(1.15f)).textColor(gui.theme().color(Role.INK))
                .wordWrap(false);
        gui.landmark(landmark, value);
        into.append(gui.column().gap(Length.rem(0.15f)).children(
                gui.text(name).textSize(Length.rem(0.75f)).textColor(gui.theme().color(Role.FAINT)),
                value));
        return value;
    }

    @Override
    public void tick(Beat beat) {
        movement.tick();
        show(beat);
    }

    @Override
    public void rest(Beat beat) {
        show(beat);
    }

    @Override
    public void wake(Beat beat) {
        show(beat);
    }

    @Override
    public void lost(Beat beat) {
        show(beat);
    }

    @Override
    public void source(String state) {
        status.text(Landmarks.STATUS_SOURCE, state);
    }

    /** Everything derived from the beat, from the whole of it, so no two readouts can disagree. */
    private void show(Beat beat) {
        movement.show(beat);
        frame.text(beat.frames() == 0 ? "—"
                : "#" + (beat.frame() >= 0 ? beat.frame() : beat.frames()) + "  (" + beat.frames() + " seen)");
        frameTime.text(Durations.text(beat.frameNanos()));
        interval.text(Durations.text(beat.intervalNanos()));
        park.text(beat.budget().isEmpty() ? "—"
                : beat.restsNotAtAll() ? "0ms: polled, no rest"
                : (beat.resting() ? "resting · " : "rested " + Durations.text(beat.restedNanos()) + " of ")
                        + beat.budget());
        wake.text(beat.wake().isEmpty() ? "—" : beat.wake());
        waker.text(beat.waker().isEmpty() ? "—" : beat.waker());
        lost.text(beat.lostRows() == 0 ? "none" : beat.lostRows() == 1 ? "1 row" : beat.lostRows() + " rows");
        stalls.text((beat.stalls() == 0 ? "none" : String.valueOf(beat.stalls()))
                + (beat.stalled() ? " · now " + Durations.text(beat.uncoveredNanos()) : ""));
    }

    /** The window's title says what is being inspected. */
    void title(String source) {
        titleBar.title(source + " — " + Inspector.TITLE);
    }

    /**
     * Show {@code builds}, {@code running}'s application's history, newest first, with the running build marked; an
     * empty list hides the strip. Any thread: writes are queued.
     */
    void history(dev.sibarum.inspector.history.Build running,
                 java.util.List<dev.sibarum.inspector.history.History.BuildStats> builds) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean listed = false;
        for (var b : builds) {
            boolean now = running != null && b.build().key().equals(running.key());
            listed |= now;
            lines.add((now ? "▸ " : "  ") + b.text() + (now ? "  (running)" : ""));
        }
        // A build never run before still heads the list, so the running build is always on it: a new commit or a
        // fresh edit is exactly the build to compare with the ones before.
        if (running != null && running.known() && !listed && !builds.isEmpty()) {
            lines.addFirst("▸ " + running.shortSource() + "  first run  (running)");
        }
        for (int i = 0; i < historyLines.length; i++) {
            boolean shown = i < lines.size();
            historyLines[i].visible(shown);
            if (shown) {
                historyLines[i].text(lines.get(i));
            }
        }
        history.visible(!lines.isEmpty());
    }

    /** Show the picker (nothing is being inspected) or the movement (something is). Any thread: writes are queued. */
    void picking(boolean picking) {
        inspecting.visible(!picking);
        picker.node().visible(picking);
    }
}
