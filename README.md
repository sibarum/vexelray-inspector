# VexelRay Inspector

DevTools for VexelRay applications, drawn as clockwork. Every working part of a running app (its frame loop,
messages, timers, animations, swapchain buffers, idle threads, GCs) is a mechanism on screen, and every mechanism maps
to a real subsystem. Built on [vexelray-gui](../vexelray-gui) through [vexelray-framework](../vexelray-framework),
from its `vexel-desktop` template, and a **framework witness** like Vexplore: the gaps it finds go upstream.

- **The plan**: [docs/roadmap.md](docs/roadmap.md) says what the Inspector is made of, what the stack has to grow
  for it, and the milestones. The data comes from the automation socket's flight recorder,
  `vexelray-gui/docs/plans/flight-recorder.md`.
- **What is not done**: [docs/TODO.md](docs/TODO.md).
- **What the framework did to us**: [docs/framework-notes.md](docs/framework-notes.md).

![The escapement at rest, replaying a calculator session](docs/screenshots/resting.png)

*The calculator demo's main loop, replayed from `samples/calculator.csv`: frame 22 took 3.74 ms, and the loop has
parked with a 200 ms budget, so the balance wheel rests and the plaque says so.*

## Status

**Milestone 1, first light: the app half.** It is built ahead of the live stream, from recordings.

| Works | Not yet |
| --- | --- |
| The **escapement**: the balance wheel swings one beat per `frame.present`, the fork snaps across and the escape wheel steps half a tooth | Frame stages in the swing (INPUT, CLOCK, APP, SETTLE): M2 |
| A **park is a rest**: the wheel stands still, and the plaque names the park's budget (`200ms`, `until woken`) | Judging a gap no park covers as a stall |
| Readouts: frame number, frame time (its `frame` span), the time since the frame before, the park and how long the last rest lasted, and what ended it (`mutation`, `injected input`, the budget running out) on which thread | The recorder's own cost |
| **Replay** of a probe CSV at its recorded pace, looping, faster or slower | Attaching: `--launch <command>` and the live `stream` verb (the seam is `RowSource`) |
| 20 tests, none of which needs a window; one builds the real tree headless | |

## Run it

```
mvn compile exec:exec "-Dapp.args=--replay=samples/calculator.csv"
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. Without `--replay` the window
opens with nothing to inspect, and the status line says so.

```
mvn compile exec:exec "-Dapp.args=--replay=run.csv --replay.speed=0.25"   # a quarter of the recorded pace
mvn compile exec:exec "-Dapp.args=--replay=run.csv --replay.loop=false"   # once, then rest
mvn compile exec:exec -Dautomation=0                                       # a driving socket on a free port
mvn test
```

### Recording something to replay

Any VexelRay application writes the rows the Inspector reads when Probe is switched on as a correlation log
(`vexelray-gui/docs/reference/automation.md` §4):

```
mvn compile exec:exec "-Dapp.jvmArgs=-Dprobe=all -Dprobe.format=csv -Dprobe.out=run.csv"
```

`samples/calculator.csv` is one, from `calculator-vexel-demo` driven by `ottermate` (a few keystrokes with pauses, so
the loop both bursts and parks). It is trimmed to the 4.9 s after the window first showed, about 1,900 rows on every
lane; that its `seq` starts at 3297 rather than 1 is the trim, not loss.

## How it is put together

The flow is one line: **source → escapement → window**.

| File | What belongs in it |
| --- | --- |
| `Recipes.java` | **what this application builds**, one `@Provides` per part: the row source, the window, the escapement, and the start. `InspectorAppWiring` is generated from it. |
| `rows/` | **the rows, with no `Gui` in them.** `Row` is the `correlations.csv` schema plus the stream's `frame` column. `RowFormat` reads a line (RFC 4180 quoting, a header row naming the columns, torn lines skipped and counted). `RowSource` is the seam: `ReplaySource` plays a file at its recorded pace on its own thread, and the live stream will be the second implementation (its javadoc says exactly what it will do). |
| `clock/` | **the mechanisms as state.** `Escapement` reads the `frame` lane (`frame.present` ticks, `loop.park` rests, the `frame` span and the first `wake` after a park) into a `Beat`, one immutable value, and tells a listener. |
| `Ui.java` | the window: the movement beside its readouts, and a status line. The escapement's listener, so everything shown is derived from one `Beat`. |
| `Movement.java` | the escapement on screen, and its animation. |
| `Parts.java` | the parts, drawn: plate, balance wheel, pallet fork, escape wheel, balance cock. |

`Inspector.java` is the entry point and its constants. `Look.java` and `Landmarks.java` are the vocabularies: colour,
and the names an automation script may depend on (`movement`, `balance`, `plaque`, `readout.*`).

### How the clockwork is drawn

Each moving part is a `Picture` (`vexelray-gui-draw`), authored once and upright in a square box centred on its
pivot, and turned with `Node.rotate`, which is a visual transform: nothing reflows and nothing is measured. A beat is
a `KronoGui.ramp` that writes three angles per frame and nothing else, and the pictures are only rebuilt when their
boxes are resized. The plate is the movement node's `picture` and the balance cock its `overlay`, so the cock is
drawn over the wheel as it is in a watch. A marched SDF viewport (drawing.md §7) would have bought lighting and
depth, which this does not need yet, at the price of a technique dependency and a pipeline. The metals are fixed
colours rather than theme roles: a movement is gold, steel and ruby in any theme.

### Threads

`ReplaySource` delivers on its own platform thread, `inspector-replay`, which spends nearly all its time parked; the
escapement and the window's listener run there. A prop written off the GUI thread is applied by the next drain, and
the swing is moved onto the timeline (`KronoGui.onTimeline`), so the angles are only ever touched there. Nothing runs
inside the frame loop.

## Taking a screenshot

Screenshots are `ottermate`'s (`vexelray-gui/vexelray-gui-automation-cli`). The plaque is the thing to wait on:

```
await plaque AT REST · 200ms
shot docs/screenshots/resting.png
```

```
ottermate --script check.txt --launch mvn.cmd -q compile exec:exec -Dautomation=0 "-Dapp.args=--replay=samples/calculator.csv"
```

`settle` works too, and returns between beats (a swing is a 130 ms ramp, and the replay thread is not on the
timeline), but it does not say *which* beat; `await` on a landmark does.

## A native executable

```
mvn -Pnative-release package -DskipTests      # target/vexelray-inspector.exe        no console, no driving socket
mvn -Pnative package -DskipTests              # target/vexelray-inspector-debug.exe  a console, and ottermate can drive it
```

From a Visual Studio developer prompt with a GraalVM JDK as `JAVA_HOME`. `InspectorApp` is written twice:
`src/edition-debug` names `AutomationStarter` and `src/edition-release` does not. Keep the two identical apart from
`starters`. Not yet built natively.

## Logging

Through `sibarum.probe.Log`, as `Log.of("vexelray-inspector.<area>")`. From a checkout the console shows info and
`target/logs/vexelray-inspector.log` has debug; under `ottermate` the file is at trace with a probe trace beside it.
`--log=debug` changes the level for one launch.
