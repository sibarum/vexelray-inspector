package dev.sibarum.inspector;

import dev.sibarum.inspector.rows.Attach;
import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Keyed;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Button;
import sibarum.kronometer.Dur;
import dev.vexelray.gui.krono.Scheduled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The running applications, to choose one to inspect: one entry per application in the discovery folder, refreshed
 * every second while the picker is shown.
 *
 * <p>Reading the folder is disk work, so it runs on the offload lane, never on the GUI thread or in the frame loop;
 * the timeline only asks for it. The list is kept by key ({@link Node#keyed}), one per application process: an
 * application that stays keeps its entry, changed in place, so a click is never lost to a refresh and the window's
 * motion sees only what changed. One that starts fades in, one that goes fades out, and the rest slide. The words
 * for an empty folder are a line of the same list, so they fade out as the first entry fades in rather than
 * vanishing before it.
 *
 * <p>An application without a link (started with {@code -Dautomation.link=off}, or before the link existed) is
 * listed with the reason and cannot be chosen.
 */
final class Picker {

    /** How often the folder is read while the picker is shown. */
    static final Dur REFRESH = Dur.ms(1000);

    /** The key of the line shown when no application is running. An application's key is never blank. */
    private static final String NONE = "";

    private final Gui gui;
    private final KronoGui krono;
    private final Consumer<Discovery.Entry> onPick;
    private final Node node;
    private final Keyed<Line> lines;
    /**
     * Each application entry's parts, by key, so a kept entry is changed in place. A key stays here for one refresh
     * after it has gone, longer than its entry takes to leave, in case it comes back while still on screen.
     */
    private final Map<String, Entry> entries = new HashMap<>();
    private volatile String filter = "";
    private volatile List<String> shown = null;
    private Scheduled refreshing;
    private boolean started;
    private boolean showing = true;

    /** One line of the list: an application, or, with no entry, the words for when none is running. */
    private record Line(String key, Discovery.Entry app) {
    }

    /** An application's entry: the button that attaches and the line under it, and the application it is for now. */
    private static final class Entry {
        final Button button;
        final Node detail;
        volatile Discovery.Entry app;

        Entry(Button button, Node detail) {
            this.button = button;
            this.detail = detail;
        }
    }

    Picker(Gui gui, KronoGui krono, Consumer<Discovery.Entry> onPick) {
        this.gui = gui;
        this.krono = krono;
        this.onPick = onPick;
        Node list = gui.column().gap(Length.rem(0.5f));
        this.lines = list.keyed(Line::key, this::build);
        this.node = gui.column()
                .width(Length.rem(34))
                .gap(Length.rem(0.9f))
                .padding(Length.rem(1.4f))
                .alignItems(AlignItems.STRETCH)
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .children(
                        gui.text("CHOOSE AN APPLICATION").textSize(Length.rem(0.8f))
                                .textColor(gui.theme().color(Role.ACCENT)),
                        list);
        gui.landmark(Landmarks.PICKER, node);
        show(List.of());
    }

    Node node() {
        return node;
    }

    /** Offer only the applications {@code --attach} named, when it named several. Blank offers every one. */
    void filter(String spec) {
        this.filter = spec == null ? "" : spec;
    }

    /**
     * Start reading the folder every {@link #REFRESH} while the picker is shown. Once, from the start recipe, so a
     * headless tree reads no disk.
     */
    synchronized void start() {
        started = true;
        watch();
    }

    /**
     * The picker is shown, or hidden behind the movement. Hidden, nothing reads the folder, so the timeline can go
     * quiet while the target does. Any thread.
     */
    synchronized void shown(boolean shown) {
        this.showing = shown;
        watch();
    }

    private void watch() {
        boolean want = started && showing;
        if (want && refreshing == null) {
            refreshing = krono.every("picker", REFRESH, () -> gui.offload().execute(this::refresh));
            gui.offload().execute(this::refresh);
        } else if (!want && refreshing != null) {
            refreshing.cancel();
            refreshing = null;
        }
    }

    /** Read the folder and show what is running. On the offload lane. */
    void refresh() {
        show(Attach.match(Discovery.live(), filter));
    }

    /** Show {@code apps}, changing the list only when what it shows changed. Any thread: node writes are queued. */
    synchronized void show(List<Discovery.Entry> apps) {
        List<String> said = new ArrayList<>();
        List<Line> next = new ArrayList<>();
        for (Discovery.Entry e : apps) {
            said.add(e.base() + (e.stream() == null ? "" : " " + e.stream()));
            next.add(new Line(e.base(), e));
        }
        if (said.equals(shown)) {
            return;
        }
        if (next.isEmpty()) {
            next.add(new Line(NONE, null));
        }
        Set<String> keep = new HashSet<>();
        for (Line l : next) {
            keep.add(l.key());
        }
        if (shown != null) {
            for (String s : shown) {
                keep.add(s.split(" ", 2)[0]);
            }
        }
        entries.keySet().retainAll(keep);
        shown = List.copyOf(said);
        lines.reconcile(next, this::update);
    }

    /** A line's node, the first time its key is seen. */
    private Node build(Line line) {
        if (line.app() == null) {
            return gui.text("No VexelRay application is running in debug mode.\n"
                            + "Choose Restart in debug mode from its title bar's plug,\n"
                            + "or start it with --automation=0.")
                    .textSize(Length.rem(0.85f)).textColor(gui.theme().color(Role.FAINT));
        }
        Button b = new Button(gui, "");
        Node detail = gui.text("").textSize(Length.rem(0.72f)).textColor(gui.theme().color(Role.FAINT));
        Entry entry = new Entry(b, detail);
        b.onPress(() -> {
            Discovery.Entry e = entry.app;
            if (e != null && Attach.unavailable(e) == null) {
                onPick.accept(e);
            }
        });
        gui.landmark(Attach.landmark(line.app()), b.node());
        entries.put(line.key(), entry);
        return gui.column().gap(Length.rem(0.2f)).children(b.node(), detail);
    }

    /** Bring an application's entry up to date: its name, whether it can be chosen, and when it started or why not. */
    private void update(Line line, Node row) {
        Entry entry = line.app() == null ? null : entries.get(line.key());
        if (entry == null) {
            return;
        }
        Discovery.Entry e = line.app();
        entry.app = e;
        String why = Attach.unavailable(e);
        entry.button.label(e.app() + "  ·  pid " + e.pid()).enabled(why == null);
        entry.detail.text((why != null ? why : "started " + e.started()) + "\n" + e.get("cwd"));
    }
}
