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

**Milestone 1, first light.** It inspects a running VexelRay application live, through the application's Inspector
link, or it plays a recording.

| Works | Not yet |
| --- | --- |
| The **escapement**: the balance wheel swings one beat per `frame.present`, the fork snaps across and the escape wheel steps half a tooth | Frame stages in the swing (INPUT, CLOCK, APP, SETTLE): M2 |
| A **park is a rest**: the wheel stands still, and the plaque names the park's budget (`200ms`, `until woken`) | Judging a gap no park covers as a stall |
| Readouts: frame number, frame time (its `frame` span), the time since the frame before, the park and how long the last rest lasted, and what ended it (`mutation`, `injected input`, the budget running out) on which thread | The recorder's own cost |
| **Live**: the picker lists the running applications from their discovery files; choosing one, or `--attach=<app>`, streams its `frame` lane over its link; when it goes away the picker comes back | Attaching by launching: `--launch <command>` |
| **Replay** of a probe CSV at its recorded pace, looping, faster or slower | Reading the other lanes (M2 on) |
| 30 tests, none of which needs a window; one builds the real tree headless, one streams from a real link | |

## Run it

```
mvn compile exec:exec "-Dapp.args=--replay=samples/calculator.csv"
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. Without `--replay` the window
opens on the picker. The picker lists every VexelRay application running with its automation socket on, read from
the discovery files in `~/.vexelray/automation/`. Choose one, and the escapement follows it live.

```
mvn compile exec:exec "-Dapp.args=--attach=calculator-vexel-demo"         # straight to a running application
mvn compile exec:exec "-Dapp.args=--attach=pid:18292"                      # one process, when several share a name
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
| `Recipes.java` | **what this application builds**, one `@Provides` per part: the first source, the switch in front of it, the picker, the window, the escapement, and the start. `InspectorAppWiring` is generated from it. |
| `rows/` | **the rows, with no `Gui` in them.** `Row` is the `correlations.csv` schema plus the stream's `frame` column. `RowFormat` reads a line (RFC 4180 quoting, a header row naming the columns, torn lines skipped and counted). `RowSource` is the seam: `ReplaySource` plays a file at its recorded pace, and `LinkSource` streams a running application over its Inspector link (`LinkClient`, from `vexelray-gui-inspector-link`). `Sources` stands in front of whichever is current, and is the one place the source changes. `Attach` says which running applications `--attach` and the picker offer, from `Discovery.live()`. |
| `Picker.java` | the running applications to choose from, read from the discovery folder every second on the offload lane, while nothing is being inspected. |
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

Whichever source is current delivers on a thread of its own, and the escapement and the window's listener run there.

- **A replay** delivers on `inspector-replay`, a platform thread that spends nearly all its time parked.
- **A live source** connects on `inspector-link`, which ends once the stream is open. The rows then arrive on
  elektro-Q's receive thread for that connection: one thread, one call at a time, in the application's order, which
  is what `RowSink` asks for. The end of a run, when the application goes away, comes on that thread too.
- **The picker** reads the discovery folder on the offload lane, asked by a one-second timer on the timeline.

A prop written off the GUI thread is applied by the next drain. The swing is moved onto the timeline
(`KronoGui.onTimeline`), so the angles are only ever touched there. Nothing runs inside the frame loop.

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
