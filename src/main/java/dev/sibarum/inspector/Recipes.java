package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.Escapement;
import dev.sibarum.inspector.rows.Attach;
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
 * <p>The driving socket is not here. It comes from {@code AutomationStarter}, which the debug edition of
 * {@code InspectorApp} names in its {@code @VexelApp} (see {@link Inspector}).
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
     *   <li>{@code --attach=<app>} or {@code --attach=pid:<n>}: a running application, live, through its Inspector
     *       link, found in the discovery folder ({@link Attach});</li>
     *   <li>neither: nothing yet, and the window shows the picker.</li>
     * </ul>
     */
    @Provides
    RowSource source(@Setting("replay") String replay,
                     @Setting(value = "replay.speed", def = "1") float speed,
                     @Setting(value = "replay.loop", def = "true") boolean loop,
                     @Setting("attach") String attach) {
        if (!replay.isBlank()) {
            return new ReplaySource(Path.of(replay), speed, loop, Pacer.SYSTEM);
        }
        if (!attach.isBlank()) {
            return Attach.initial(Discovery.live(), attach);
        }
        return RowSource.none("choose an application to inspect");
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
    Ui ui(Gui gui, KronoGui krono, TitleBar titleBar, Sources sources, Picker picker) {
        Ui ui = new Ui(gui, krono, titleBar, picker);
        ui.title(sources.describe());
        zoomShortcuts(gui);
        return ui;
    }

    /** The main loop's state, read from the rows; it tells the window. Where the rows go, so it is a {@link RowSink}. */
    @Provides
    RowSink escapement(Ui ui) {
        return new Escapement(ui);
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
    Started start(Shell shell, Sources sources, RowSink escapement, Ui ui, Picker picker) {
        sources.listen(new Sources.Listener() {
            @Override
            public void attached(RowSource now) {
                ui.title(now.describe());
            }

            @Override
            public void began(String origin) {
                ui.picking(false);
            }

            @Override
            public void ended(String why) {
                ui.title("nothing attached");
                ui.picking(true);
            }
        });
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
