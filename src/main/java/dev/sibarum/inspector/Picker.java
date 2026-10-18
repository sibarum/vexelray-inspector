package dev.sibarum.inspector;

import dev.sibarum.inspector.rows.Attach;
import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Button;
import sibarum.kronometer.Dur;
import dev.vexelray.gui.krono.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The running applications, to choose one to inspect: one entry per application in the discovery folder, refreshed
 * every second while the picker is shown.
 *
 * <p>Reading the folder is disk work, so it runs on the offload lane, never on the GUI thread or in the frame loop;
 * the timeline only asks for it. The entries are rebuilt only when the set of applications changed, so a click is
 * never lost to a refresh that replaced the button under the pointer.
 *
 * <p>An application without a link (started with {@code -Dautomation.link=off}, or before the link existed) is
 * listed with the reason and cannot be chosen.
 */
final class Picker {

    /** How often the folder is read while the picker is shown. */
    static final Dur REFRESH = Dur.ms(1000);

    private final Gui gui;
    private final KronoGui krono;
    private final Consumer<Discovery.Entry> onPick;
    private final Node node;
    private final Node list;
    private final Node empty;
    private volatile String filter = "";
    private volatile List<String> shown = List.of();
    /** The entries shown now, to remove when the set changes: {@code children} only appends. */
    private final List<Node> entries = new ArrayList<>();
    private Scheduled refreshing;

    Picker(Gui gui, KronoGui krono, Consumer<Discovery.Entry> onPick) {
        this.gui = gui;
        this.krono = krono;
        this.onPick = onPick;
        this.list = gui.column().gap(Length.rem(0.5f));
        this.empty = gui.text("No VexelRay application is running with its automation socket on.\n"
                        + "Start one with -Dautomation=0 (or --automation=0), and it appears here.")
                .textSize(Length.rem(0.85f)).textColor(gui.theme().color(Role.FAINT));
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
                        empty,
                        list);
        gui.landmark(Landmarks.PICKER, node);
    }

    Node node() {
        return node;
    }

    /** Offer only the applications {@code --attach} named, when it named several. Blank offers every one. */
    void filter(String spec) {
        this.filter = spec == null ? "" : spec;
    }

    /** Start reading the folder every {@link #REFRESH}. Once, from the start recipe, so a headless tree reads no disk. */
    void start() {
        if (refreshing == null) {
            refreshing = krono.every(REFRESH, () -> gui.offload().execute(this::refresh));
            gui.offload().execute(this::refresh);
        }
    }

    /** Read the folder and show what is running. On the offload lane. */
    void refresh() {
        show(Attach.match(Discovery.live(), filter));
    }

    /** Show {@code apps}, rebuilding the entries only when the set changed. Any thread: node writes are queued. */
    synchronized void show(List<Discovery.Entry> apps) {
        List<String> keys = new ArrayList<>();
        for (Discovery.Entry e : apps) {
            keys.add(e.base() + (e.stream() == null ? "" : " " + e.stream()));
        }
        if (keys.equals(shown)) {
            return;
        }
        shown = List.copyOf(keys);
        synchronized (entries) {
            entries.forEach(Node::remove);
            entries.clear();
            for (Discovery.Entry e : apps) {
                Node n = entry(e);
                entries.add(n);
                list.append(n);
            }
        }
        empty.visible(apps.isEmpty());
    }

    /** One application: a button that attaches, with when it started and where; or the reason it cannot. */
    private Node entry(Discovery.Entry e) {
        String why = Attach.unavailable(e);
        Button b = new Button(gui, e.app() + "  ·  pid " + e.pid()).enabled(why == null);
        if (why == null) {
            b.onPress(() -> onPick.accept(e));
        }
        gui.landmark(Attach.landmark(e), b.node());
        Node detail = gui.text((why != null ? why : "started " + e.started()) + "\n" + e.get("cwd"))
                .textSize(Length.rem(0.72f)).textColor(gui.theme().color(Role.FAINT));
        return gui.column().gap(Length.rem(0.2f)).children(b.node(), detail);
    }
}
