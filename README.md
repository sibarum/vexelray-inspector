# VexelRay Inspector

DevTools for VexelRay applications: plain panels on a timeline that can be rewound ([docs/design.md](docs/design.md)).

A VexelRay desktop application, generated from the `vexel-desktop` template and being rebuilt for VexelRay 0.2. R1 is
under way: today it attaches to the newest application running with its link open and draws its clocks' lanes
on a live timeline, the present at the right edge. Everything else in the design is still to come.

## Run it

From this folder, the parent of the two modules:

```
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. The window comes up where you
last left it, at 1180x720 the first time.

It inspects another VexelRay application, which has to be running with its link open and its probe recording: start
that one with `--automation=0 -Dprobe=all -Dprobe.format=csv` (for one built from this template,
`mvn compile exec:exec -Dautomation=0 "-Dapp.jvmArgs=-Dprobe=all -Dprobe.format=csv"`). Until one is, the status line
says so, and the Inspector looks again every two seconds.

```
mvn compile exec:exec -Dapp.args=120                      # 120 frames and quit
mvn compile exec:exec -Dautomation=0                      # a driving socket on a free port (see Taking a screenshot)
mvn test                                                  # the bench, and the real tree headless
```

## A native executable

Two builds, made with GraalVM native-image (a GraalVM JDK as `JAVA_HOME`, and Visual Studio with the "Desktop
development with C++" workload):

```
native-build.cmd -Pnative-release package -DskipTests   # vexelray-inspector-gui/target/vexelray-inspector.exe        what ships: no console
native-build.cmd -Pnative package -DskipTests           # vexelray-inspector-gui/target/vexelray-inspector-debug.exe  a console: prints the automation port line
```

Both carry the automation sockets, off until asked for ([Automation](#automation)), so ottermate can drive either.

On Linux there is one build, with no console subsystem or icon step to choose, and plain `mvn`:

```
mvn -Pnative-linux package -DskipTests                  # vexelray-inspector-gui/target-linux/vexelray-inspector
```

`native-build.cmd` is `mvn` inside a Visual Studio developer environment: native-image needs the MSVC `link.exe` and
the icon step needs `rc.exe`, and neither is on `PATH` in a plain shell. Under Git Bash it is worse, since coreutils'
own `link.exe` comes first. The script finds `vcvars64.bat` with `vswhere`, loads it, and runs `mvn` with the
arguments it was given (`./native-build.cmd ...` from Git Bash). Plain `mvn` works too from a developer prompt; from
anywhere else the icon step stops and says to use the script.

This program's own native-image metadata is `vexelray-inspector-gui/src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
and it is short because almost nothing is this program's: the libraries register their own, and the processor
registers the icon. If a native run fails on something the JVM run did not (a `Missing...RegistrationError`), run it
on a JVM under `-agentlib:native-image-agent=config-output-dir=...`, do the thing that failed, and add
only the entries that name this program.

## The icon

`vexelray-inspector-gui/src/main/rc/vexelray-inspector.ico` is the application's mark, and it is one file read three ways: the window and its
taskbar button wear it (`@VexelApp(icon = Inspector.ICON)`; the pom puts it on the class path), and so does the
title bar the framework draws; the `.exe` links it (`src/main/rc/vexelray-inspector.rc`), and so Explorer, a shortcut and a
pinned button show it too. It starts as the framework's "new app" mark, whose source is `vexelray-inspector.svg` beside it.

A second window — a viewer, a settings window — wears the window mark, on the taskbar and in its title bar, with
nothing to write: declare it in a recipe with `windows.named(...)`, and the framework builds its tree and claims it
wearing `shell.windowMark()`. That mark is this same one unless
a `vexelray-inspector-window.ico` sits beside it, which is then found by name and registered for the native image with
the mark. The VexelRay suite's applications ship one: a band of the application's colour over its glyph, so a
second window reads as its own and is still told apart from the main one.

To make it yours, draw a 64x64 SVG and render it at every size Windows asks for, 16 to 256 px, into that `.ico` —
`vex-suite-common`'s `tools/Ico.java` does exactly this, from each size's own rasterisation rather than shrinking the
largest. Replace the file, and the window and the executable change together. An `.ico` or `.png` that is missing is
a compile error; one that does not decode costs the window its mark, logged, and nothing else.

Run from `mvn exec:exec`, the process is `java.exe`, so the window and its taskbar button wear the mark but Windows
groups the button with other Java programs, and pinning it pins Java. The native executable has neither problem.

## Using it

The window shows five lanes on one time axis, ten seconds across at first, and a line of readouts under them:

| Lane | Shows |
| --- | --- |
| **Input** | each time the application took input from a device; input an automation verb injected, dimmed |
| **Main thread** | each pass of the main loop, from when it started to when it ended |
| **Logic** | each tick of the logic lane (empty under `--logic=main`, framework-notes FN-8) |
| **Drawing** | each draw, from recording to present; a draw held for the ceiling (faint) or for the GPU (caution) |
| **GPU** | what the presenter waited on: the fence, the DXGI waitable, a swapchain image |

The readouts count the second before the cursor: draws, draws held by reason, ticks and their median and 95th
percentile, and the rows the link lost. Only a run started with `-Dprobe=all` records ticks and the GPU
(framework-notes FN-9). While rows are arriving the lanes follow the present at their right edge; when the application
goes away they stop where they were, and the status line says what happened.

| Key | Does |
| --- | --- |
| Space | pause: the lanes slow to a stop; paused, follow the present again, catching up with it |
| Left / Right | step the cursor onto the focused lane's previous or next event (pausing first if following) |
| Shift+Left / Shift+Right | step the cursor back or forward a tenth of what the lanes span |
| = / - | zoom in or out about the right edge: half or twice the time across, from 10 ms to a minute |
| Click a lane | focus it (its name brightens), and put the cursor on its event nearest the click, if one is near |

Paused, the lanes show the run as it was at the cursor, which is at the right edge, and so do the readouts; the status
line says where the cursor is, counted from the run's first row. On an event, it is drawn over the others in the
text's colour, and the status line says what it was, how long it took and when it ended. Each event is drawn as long as
it took, with a pixel-wide line at its end so that one far shorter than a pixel still shows; zoom in and a busy
stretch opens up into separate events. The last minute is kept, and stepping stops at either end of it. Rows go on
arriving and being kept while paused.

Nothing on the lane jumps: every change of where it is going or how much it spans, a key held down included, glides
there with no corner in its speed or in any derivative of it, and lands exactly (design.md 9.8). Under the system's
reduced-motion setting it goes straight there. Hovering an event shows nothing yet (framework-notes FN-7). Choosing
which application to inspect, and the Sequence mode, are R1's later slices.

## How it is put together

Two modules under this folder, split where the dependency edge falls, and five kinds of part that never hold each
other: **components** (logic, reached only by messages: the bench runs on the framework's logic lane), **views**
(presentation, run on the GUI thread in each frame, `@View`), **jobs** (slow work on the offload lane, a request in
and a reply out), **sources** (something that keeps producing from outside for as long as it runs, on a thread of its
own), and **recipes** (a part built once and handed to whoever takes it). The parts talk by messages, and every
message is in one file. Read these in this order.

**`vexelray-inspector-core`, the controller.** It builds against no widget, so its tests need no window.

| File | What belongs in it |
| --- | --- |
| `Messages.java` | **every message the parts send each other**, grouped by who sends it to whom, each record beside its topic. A record crosses as itself, so it holds only immutable values; a list it holds is copied in its constructor. Every time in it is on the machine's monotonic clock. |
| `Bench.java` | **the controller**: which run is inspected, what has been read of it, and the model the panels show, as declared steps on one immutable state, held by a ledger. Each message it hears runs one step, and each step that changes what is shown publishes the `Timeline` the view draws, as of the cursor. |
| `Model.java` | what the panels show at one moment, and `fold`, the one pure function that moves it on by some rows. A new lane or readout is a field here. |
| `Tape.java` | the last minute's rows, a second at a time beside a checkpoint of the model: the model as of any moment is recomputed from it by the same `fold`. |
| `ListRuns.java` | the job that reads the discovery folder: which applications are running with a link, this one left out. |
| `LinkRows.java` | the source that reads one run's rows over its link for as long as it is attached, setting each row on the machine's clock as it crosses. |
| `Durations.java` | a probe row's duration (`51.68ms`) read back into nanoseconds: a pure function a step may call. |
| `Core.java` | the list of this module's component, job and source. The processor compiling `-gui` sees only `-gui`'s sources, so this list, named among `InspectorApp`'s starters, is how the wiring hears of them. A new part goes on it. |

**`vexelray-inspector-gui`, the view and the framework integration.**

| File | What belongs in it |
| --- | --- |
| `Recipes.java` | **what this module builds.** One `@Provides` method per part; `InspectorAppWiring`, which builds them and the components, jobs and sources in order, is generated while it compiles. A part's phase is the latest phase of anything it takes, so there is none to declare. |
| `View.java` | **the view**, a `@View`: told the bench's `Timeline`, it builds the lanes' picture; asked by every frame (`@Draw`), it slides the lanes to where the motion has it now. Its keys come to it and go on to the bench as `Pause` and `Step`, with the moment the lanes would rest at, which only it knows, and which lane is focused. Told the `Readouts`, it writes their line. It decides nothing. |
| `Glide.java` | **how the timeline moves**: smooth paths of time, and handovers between them that are smooth in every derivative and land exactly. Pure functions; its constants were set by eye, and say why. |
| `Keys.java` | what a key chord sends the view. |
| `Ui.java` | the window: the title bar, the timeline's lanes and the status line. Holds no state; it shows what the view hands it. |

`Inspector.java` is the entry point and the constants, and nothing else. The application edge — input, the
clipboard, window memory, the clock, the frame loop with its wakes and its pacing, the dialogs, the command line and
the shutdown order — is `vexelray-framework`'s. `Look.java`, `Type.java` and `Landmarks.java` are the three
vocabularies: colour, size, and the names an automation script is allowed to depend on.

### Phases

A phase is a **correctness** constraint rather than a scheduling detail, and having them as a type is what makes the
constraints structural instead of remembered:

| phase | what exists by then |
| --- | --- |
| `CONFIG` | the settings store and the look — values, before there is a `Gui` to apply them to |
| `MODEL` | what the application knows, before there is anything to draw it with |
| `GUI` | the `Gui` and the clock; the theme applied and the zoom range set, before the first widget |
| `TREE` | the widgets: the first phase a part is handed the `Gui`, so anything taking it lands here. Buildable with no window, which is what lets a test build it with no GPU |
| `WINDOW` | the device and the window handle. Main-thread from here on |
| `ATTACH` | anything that needed the handle: chrome controls, the close gate, the driving socket |

Open `vexelray-inspector-gui/target/generated-sources/annotations/.../InspectorAppWiring.java` after a build to see which part landed where.

### Threading, in one paragraph

The GUI loop runs on the main thread, which owns Vulkan, the window and present, and nothing of this application's
runs there but the view, which is presentation and nothing else (threading.md, T7.4 and T7.6). The bench runs on the
framework's logic lane and the view on the GUI thread, in each frame's widget tick; each handles one message at a
time and owns its state outright, so there is no lock anywhere in the application. The bench's state is changed only
by its declared steps, which the processor reads: a step that touches the disk, waits or reaches outside itself does
not compile. Reading the discovery folder is a job on the offload lane, and reading a run's link is a source on a
thread of its own, so neither a slow disk nor a quiet link ever holds a frame.

Two things follow from messages, and both are written where they bite. **One sender's messages to one receiver keep
their order**: one run's batches reach the bench in the order the link sent them, because one thread sends them.
**Jobs are in no order at all**: so the bench decides from each `Runs` as it comes, and a late one changes nothing once
a run is attached. The processor checks every message it can see: a mailbox's payload is deeply immutable, a topic
carries one type, and no cycle of sends can block.

### The live timeline

The one thing on screen that moves, and the frame contract's first application witness (vexelray-gui's
`docs/plans/frame-contract.md`). **Told builds, asked chooses**: when the bench's `Timeline` arrives, the view builds
the lane's picture and decides the motion: following the present, or stopping somewhere, reached by a handover from
whatever motion is in progress (`Glide`). Its one state field, a `Strip`, holds the picture's edge moment and the
motion. On every frame, the view's `@Draw` method works out where the motion puts the lane's right edge at the frame's
present and slides the lane by how far that is from the picture's edge, and asks for the next frame while anything
moves. The draw reads only its state and the frame and allocates nothing, which the processor checks. Both ends are on
the machine's monotonic clock: the link says where the run's rows count from, `LinkRows` adds it, and the frame's
`monoNanos` is the same clock. When the lane has come to rest nothing is due, so it draws nothing, and
`ottermate settle` succeeds only then: while it follows a live run, press Space first, or wait with `await` instead.

**No jumps** (design.md 9.8). A handover mixes the speed of the old motion into the new one by a step that is smooth
in every derivative and flat in all of them at both ends, and mixes in the remaining distance by the same step, so it
starts with no corner, lands exactly, and a pause, which stops where the lane would coast to, never goes back. A
handover interrupted is the motion the next one starts from. `GlideTest` samples a motion through five interruptions
and holds that neither speed nor acceleration jumps. The keys go to the view before the bench, because the moment a
pause stops at is where the lane comes to rest, which only the view knows.

### Rewinding

Time is a parameter of what is shown (design.md section 8). The bench folds each batch onto its live `Model` and keeps
it on the `Tape`, with a checkpoint every second, and the model as of any moment is that second's checkpoint with its
rows up to the moment folded on, by the same `fold`: exactly what it was then, which `BenchTest` holds for every
moment of a run crossing many checkpoints. The lanes show every event kept and put the cursor at their right edge, so
that at rest they show the run as of the cursor and while they glide they show what they pass. The readouts are
computed from the model as of the cursor, so they rewind with it.

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to have
happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-framework-api`, `vexelray-gui-core` | all `-core` builds against: the framework's annotations, and the values (a span, a colour, the settings store) and the bus that are safe from any thread |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that checks both modules and writes the wiring |
| `vexelray-gui-widget` | the widgets: `Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`, `Cues` |
| `vexelray-gui-krono` | the clock: every transition and cue |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

## Automation

VexelRay Inspector can be driven and read from outside, by a script, a macro or an agent, through the framework's two loopback
sockets: **remote control** (the keys, clicks and reads a person has) and **diagnostics** (the flight recorder, the
log, the Inspector). Both are in every build, the release one too, and both start off.

- **Switched on by hand**: the plug in the title bar opens the switches, one per socket, each with a port (blank for
  a free one).
- **Switched on at launch**, for an agent that means to: `--automation=0` (both, on a free port),
  `--automation.control=<port>`, `--automation.diagnostics=<port>`, or `VEXELRAY_AUTOMATION=0` in the environment.
- **Not there at all**: `--automation.available=none` (or `control`, or `diagnostics`), or
  `VEXELRAY_AUTOMATION_AVAILABLE` in the environment. A socket that is not available cannot be switched on by
  anything, and with neither available the plug is gone.

Whatever turned it on, **the plug's lamps say what each socket is doing**, remote control's (red) left of the plug and
diagnostics' (blue) right:

| Lamp | Means |
| --- | --- |
| none | off, and nothing has connected to it this session |
| ring | open, and nothing has connected yet |
| filled ring | open, and something is connected now |
| ring with a grey dot | open; something was connected and has gone |
| grey dot | off, after something had connected to it this session |

The plug's tooltip and the switches' window say the same in words, with the port and how many are connected.

## Taking a screenshot

Screenshots are `ottermate`'s, and only its: it photographs the running window on the application's own device, so
the picture is right about content as well as chrome. There is no `--capture` flag in this project on purpose.

```
ottermate shot out.png --launch mvn compile exec:exec -Dautomation=0
```

Say what the picture is *of* with options, and `ottermate` sets it first and stops if it cannot. Everything before
`--launch` is ottermate's; everything after it is the application's command line (on Windows, `mvn.cmd`):

```
ottermate --zoom 1.5 --dpi 2 --size 1600x900 shot big.png --launch ...   # pixels
ottermate --size 36emx20em shot smallest.png --launch ...                # the minimum, in em
```

The landmarks in `Landmarks.java` are the names a script uses: `await status.run Inspecting` waits for the first rows
of an attached run, `lanes` is the box the lanes are drawn in, and `readouts` the line of numbers under it.
`ottermate help` lists every verb.

## Logging

The framework configures logging for you, before anything else runs, and the defaults follow what kind of run it is:

| Run | Console | File |
| --- | --- | --- |
| a test | warnings | none |
| from your checkout (`mvn exec:exec`) | info | `vexelray-inspector-gui/target/logs/vexelray-inspector.log`, debug |
| driven by `ottermate` or `-Dautomation` | debug | `…/vexelray-inspector.log`, **trace**, with a probe trace beside it |
| a native image | warnings | `~/.vexelray-inspector/logs/vexelray-inspector.log`, info |

Write to it with a logger, not `System.out` — the jobs do, as `vexelray-inspector.files`:

```java
private static final Log LOG = Log.of("vexelray-inspector.files");   // sibarum.probe.Log
LOG.warn("could not save {}", path, exception);               // the exception goes last; its stack reaches the file
```

`--log=debug` (or `off`) changes the level for one launch, and `-Dlog.level.vexelray-inspector.files=trace` raises one
logger. Levels, settings and the rules for what belongs at which level are in `atchung/docs/logging.md`.

## The three documents

`docs/framework-notes.md` and `docs/TODO.md` start nearly empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two. `docs/retrospective.md` is filled in at the end of
each milestone: the pass over the notes that looks for what they have in common, and the one the framework reads
first.
