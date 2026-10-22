package dev.sibarum.inspector;

import dev.vexelray.framework.shell.VexelApplication;


/**
 * DevTools for VexelRay applications, drawn as clockwork.
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. That is all it is, and the reason is
 * worth knowing before anything else here: <b>the application edge is the framework's.</b> Opening an input
 * backend, installing a clipboard, remembering where the window was, attaching a clock, wiring the frame loop with
 * its wakes and its pacing, installing the dialogs, parsing the command line and closing everything in the right
 * order are all done for you.
 *
 * <p>What this application builds — an escapement driven by a stream of probe rows — is in {@link Recipes}, one
 * method per part. {@code InspectorAppWiring}, which builds those parts in order, is generated from them and from
 * the {@code @VexelApp} on {@code InspectorApp} while the project compiles — so the facts below are stated once,
 * here, and the wiring reads them.
 *
 * <pre>
 * Inspector --replay=run.csv      play a probe CSV at its recorded pace, looping (see docs/roadmap.md, M1)
 * Inspector --replay.speed=4      four times as fast; --replay.loop=false plays it once
 * Inspector --launch mvn.cmd compile exec:exec -Dautomation=0
 *                                 start an application, inspect it live, and stop it on close; the command is the
 *                                 rest of the line (or one quoted --launch="..." value)
 * Inspector --attach=demo         a running application, live, through its Inspector link
 * Inspector --attach=pid:18292    one process, when several share a name
 * Inspector                       the picker: every application running with its automation socket on (a debug run)
 * Inspector --key=value           override a setting for this launch
 * </pre>
 *
 * <p>A misspelled flag is refused by name with the alternatives listed, rather than a stack trace before any
 * window. Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><b>{@code InspectorApp} declares the application.</b> Its {@code starters} is everything configuring this
 * application beyond {@link Recipes}, listed rather than discovered: {@code AutomationStarter}, the Inspector's own
 * automation sockets, in every build, the release one included — off until the launch or the title bar's plug
 * switches them on.
 */
public final class Inspector {

    /** The application's own name, which is what its settings directory is called. Stable across releases. */
    static final String APP = "vexelray-inspector";

    /** The window's title. */
    static final String TITLE = "VexelRay Inspector";

    /** Window size on a first run, in the engine's logical coordinates. */
    static final int W = 1040;
    static final int H = 940;

    /**
     * The mark, from the class path's root: {@code src/main/rc/vexelray-inspector.ico}, which the pom also puts there and
     * the executable links. It starts as the framework's own; replace the file, and the window, its taskbar button
     * and the {@code .exe} all change together.
     */
    static final String ICON = "/vexelray-inspector.ico";

    /**
     * The smallest this UI is still coherent at, in root ems — a floor, not the design size: the movement and
     * its readouts side by side, neither clipped.
     *
     * <p>Named here rather than written at each use because it is read from two places that have to agree:
     * {@link Recipes#look} declares it to the framework, and a screenshot at exactly the minimum
     * ({@code ottermate --size 51emx35em shot}) is the picture that shows a panel outgrowing it.
     */
    static final float MIN_W_EM = 51;
    static final float MIN_H_EM = 58;

    /**
     * Entry point.
     *
     * <p>There is no screenshot flag here on purpose. A picture of a running window, at a chosen size, zoom and
     * density, is {@code ottermate}'s, because it photographs the application's own device and so is correct
     * about content as well as chrome. See the README, <i>Taking a screenshot</i>.
     */
    public static void main(String[] args) {
        String[] cleaned = dev.sibarum.inspector.rows.LaunchSource.fold(
                java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new));
        VexelApplication.run(new InspectorAppWiring(), cleaned);
    }

    private Inspector() {
    }
}
