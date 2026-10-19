package dev.sibarum.inspector;

import dev.sibarum.inspector.circuit.BusGraph;
import dev.sibarum.inspector.clock.Escapement;
import dev.sibarum.inspector.history.History;
import dev.sibarum.inspector.history.Recorder;
import dev.sibarum.inspector.history.Runs;
import dev.sibarum.inspector.rows.Attach;
import dev.sibarum.inspector.rows.LaunchSource;
import dev.sibarum.inspector.rows.Pacer;
import dev.sibarum.inspector.rows.ReplaySource;
import dev.sibarum.inspector.rows.RowSink;
import dev.sibarum.inspector.rows.RowSource;
import dev.sibarum.inspector.rows.Sources;
import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <p>{@code InspectorAppWiring} is generated from this while the project compiles and calls each method once, in the
 * phase its parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the row source
 * exists before the {@code Gui}; the window's contents wait for the {@code Gui}, its clock and its title bar; and
 * the start, which takes the {@code Shell}, comes last, when the window exists. Open the generated file after a
 * build to see the order.
 *
 * <p>The flow is one line: <b>source → escapement → ui</b>. The {@link RowSource} delivers rows on its own thread to
 * the {@link Escapement}, which turns the frame lane into {@code Beat}s and hands each to the {@link Ui}.
 *
 * <p>The automation sockets are not here. They come from {@code AutomationStarter}, which {@code InspectorApp}
 * names in its {@code @VexelApp} (see {@link Inspector}).
 */
@Configuration
final class Recipes {

    /** The look, and the smallest window this UI is still coherent in. */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(Inspector.MIN_W_EM), Length.rem(Inspector.MIN_H_EM));
    }

    /**
     * Where the rows come from first:
     * <ul>
     *   <li>{@code --replay=<file>}: a probe CSV played at its recorded pace, faster with {@code --replay.speed=4},
     *       once with {@code --replay.loop=false};</li>
     *   <li>{@code --launch="<command>"}: an application the Inspector starts, then inspects live, and stops when it
     *       is done with it ({@link LaunchSource}); the command must switch automation on, and
     *       {@code --launch.timeout=<seconds>} is how long it has to get ready;</li>
     *   <li>{@code --attach=<app>} or {@code --attach=pid:<n>}: a running application, live, through its Inspector
     *       link, found in the discovery folder ({@link Attach});</li>
     *   <li>none of them: nothing yet, and the window shows the picker.</li>
     * </ul>
     */
    @Provides
    RowSource source(@Setting("replay") String replay,
                     @Setting(value = "replay.speed", def = "1") float speed,
                     @Setting(value = "replay.loop", def = "true") boolean loop,
                     @Setting("launch") String launch,
                     @Setting(value = "launch.timeout", def = "120") int launchTimeout,
                     @Setting("attach") String attach) {
        if (!replay.isBlank()) {
            return new ReplaySource(Path.of(replay), speed, loop, Pacer.SYSTEM);
        }
        if (!launch.isBlank()) {
            return new LaunchSource(LaunchSource.split(launch), launchTimeout, Discovery::live);
        }
        if (!attach.isBlank()) {
            return Attach.initial(Discovery.live(), attach);
        }
        return RowSource.none("choose an application to inspect");
    }

    /**
     * The bus graph the circuit is drawn from (docs/bus-circuit.md): {@code --bus.graph=<file>}, a hand-written graph
     * until applications declare theirs over the link. None draws the framework chip alone. A file that is not a
     * graph stops the start, naming the line.
     */
    private static BusGraph busGraph(String file) {
        if (file.isBlank()) {
            return BusGraph.NONE;
        }
        try {
            return BusGraph.parse(java.nio.file.Files.readString(Path.of(file)));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("--bus.graph=" + file + ": " + e.getMessage(), e);
        }
    }

    /** The source being read now, starting with {@link #source}: the picker switches it, and nothing else does. */
    @Provides
    Sources sources(RowSource source) {
        return Sources.of(source);
    }

    /**
     * The running applications to choose from. Choosing one attaches to it. {@code --attach} naming several shows
     * only those.
     */
    @Provides
    Picker picker(Gui gui, KronoGui krono, Sources sources, @Setting("attach") String attach) {
        Picker picker = new Picker(gui, krono, app -> sources.attach(Attach.source(app)));
        picker.filter(attach);
        return picker;
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window, so a test can build
     * the real tree headless ({@code VexelApplication.tree}).
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, TitleBar titleBar, Sources sources, Picker picker,
          @Setting("bus.graph") String graph) {
        Ui ui = new Ui(gui, krono, titleBar, picker, busGraph(graph));
        ui.title(sources.describe());
        zoomShortcuts(gui);
        return ui;
    }

    /**
     * The runs being inspected, measured and kept by build: one CSV line per run, per application, in
     * {@code ~/.vexelray-inspector/history/}. Read and written on the offload lane; what is read goes to the window.
     */
    @Provides
    Runs runs(Gui gui, Ui ui) {
        History history = new History(AppHome.of(Inspector.APP).dir().resolve("history"));
        return new Recorder(history, gui.offload(), ui::history);
    }

    /**
     * Where the rows go ({@link Show}): measured as they arrive, by an escapement whose beats the history keeps; and
     * shown on the show clock, by an escapement the window shows and by the bus's beads, released together.
     */
    @Provides
    RowSink escapement(KronoGui krono, Ui ui, Runs runs) {
        return new Show(krono, new Escapement(runs), new Escapement(ui), ui.bus());
    }

    /**
     * Start the rows flowing, and the picker reading the folder. Takes the {@code Shell} to be last: a tree built
     * headless for a test must not start a replay thread or read the disk. A provider has to return something, hence
     * {@link Started}.
     *
     * <p>The window shows the picker whenever nothing is flowing: at the start with nothing to inspect, and when a
     * live application goes away or a replay ends for good. It shows the movement as soon as a run begins.
     */
    @Provides
    Started start(Shell shell, Sources sources, RowSink escapement, Ui ui, Picker picker, Runs runs) {
        sources.listen(new Sources.Listener() {
            @Override
            public void attached(RowSource now) {
                ui.title(now.describe());
                // The run of whatever was attached before ends here, and this application's builds are shown.
                runs.finish();
                runs.look(now.build().orElse(null));
            }

            @Override
            public void began(String origin) {
                ui.picking(false);
                // A launched application is only known once it is up: name it, and show its builds, now.
                RowSource now = sources.current();
                ui.title(now.describe());
                now.build().ifPresent(runs::look);
                runs.begin(now.build().orElse(null));
            }

            @Override
            public void ended(String why) {
                ui.title("nothing attached");
                ui.picking(true);
                runs.finish();
            }
        });
        // Closing the Inspector keeps the run being measured, before the lanes it would otherwise be written on stop.
        shell.disposer().register(runs);
        picker.start();
        sources.start(escapement);
        return new Started();
    }

    /** That {@link #start} has run. */
    static final class Started {
    }

    /** Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three. */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }
}
