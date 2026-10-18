# Inspector roadmap

The Inspector is DevTools for VexelRay applications. It shows every working part of a running app in one view: messages,
event storms, timers, animations, screen updates, devices, swapchain buffers, idle threads and GCs. The aim is that a
problem is obvious at a glance instead of something to guess at. It is drawn as live, animated clockwork: precise,
mechanical and tangible.

This document says what the Inspector is made of, what the stack has to grow before it can work, and the order to build
it in. Proposed 2026-10-17.

## Shape

It has two parts, and the first one is not in this repository.

- **The debug port** is the existing automation socket, extended into an instrument panel. That work is
  [`vexelray-gui/docs/plans/flight-recorder.md`](../../vexelray-gui/docs/plans/flight-recorder.md), and it adds four
  things:
  - a **recorder**: a ring of probe rows held inside the application;
  - **query verbs** over that ring that an agent can read (`frames`, `why`, `diagnose`, and one verb per domain:
    `bus`, `kron`, `gpu`, `ledger`, `threads`, `heap`);
  - **many observers, one hand**: several connections can watch at once, and only the one holding the pointer can
    act;
  - a **live stream** of the same rows, in two forms: typed intrabuf records over elektro-Q for the Inspector, and
    CSV text on the automation socket for agents and files.

  It ships wherever automation does: in the debug edition, behind `--automation`, and never in a release build.
- **The Inspector** is a VexelRay app, built from the `vexel-desktop` template, that runs out of process. It is the
  port's richest client. It draws from the typed stream, and sends query verbs over the same link when you select
  something. Running
  apart means it disturbs the target as little as possible, outlives a target crash, and can inspect itself.

An agent uses the same port through `ottermate`, which relays verbs it does not know. Whatever the Inspector can
show, an agent can ask, as text with a fixed shape. Both read the same ring, so they cannot disagree. Because the
port allows several observers, an agent can run `diagnose` while a person watches the clockwork, or drive the app
on one connection while it watches on another.

Two values tie everything together:

- **One clock:** the target's monotonic nanoseconds, the time base Probe already uses.
- **One correlation key:** the frame number. The recorder stamps every row on every lane with the frame it happened
  in. That stamp is what lets the clockwork line up.

### Why the automation socket, not a socket of its own

An earlier draft of this roadmap gave the Inspector its own socket. Extending the automation socket is better:

- **An agent gets everything for free.** ottermate already drives every VexelRay app through this port.
- **There is one opt-in, one port, and one loopback rule** to reason about.
- **The recording and the driving share one timeline.** The agent's commands are already probe rows, so they appear
  in the clockwork, in order with what they caused.

The automation socket's limits are fixed in the port plan rather than worked around: it gains several observers
with one hand, and a stream beside its request/response verbs.

The one place the Inspector leaves the automation socket is its stream. A spike measured the options (atchung
[`docs/spikes/elektroq-inspector-stream.md`](../../atchung/docs/spikes/elektroq-inspector-stream.md)):

- **Text would keep up.** CSV text sustains 27 times the real peak of a full trace, so speed is not why the
  Inspector switches.
- **Text can't carry everything the Inspector needs.** It has no shape for snapshots of maps and lists, no typed
  reply for a query, and no loud failure when two builds disagree on a schema. Its loopback port can be dialled by
  any local process.
- **So the Inspector uses intrabuf records over elektro-Q,** through a Unix domain socket that the discovery file
  advertises. elektro-Q handles all four points, and it is the direction the stack has already taken.
- **Agents, `ottermate` and files keep the text form.** The ring and its per-connection sender are shared, so the two
  forms cannot disagree about what happened.
- **Recordings stay CSV.** `--replay` reads probe CSV files, and the Inspector treats files and live streams the same
  way at the level of rows.

## The clockwork

Each mechanism maps one-to-one onto a real subsystem. Nothing in it is decoration.

| Part | Subsystem | What you see |
| --- | --- | --- |
| **Escapement and balance wheel** | `GuiApp` main loop | One tick per frame. The swing is split into frame stages (INPUT, CLOCK, APP, SETTLE; dispatch, drain, layout, resolve, draw, present). While the loop parks, the wheel rests, labelled with the wake reason that ends the rest. |
| **Gear train** | Lanes: main, timeline, handler, offload, component threads | A gear turns when its thread is busy, stands still when it is parked, and shows red teeth when it stalls. Meshing shows hand-offs between lanes. |
| **Conveyors and beads** | atchung topics | Each message is a bead. Delivery mode decides the conveyor: inline is a rigid shaft, async a belt, pumped a hopper. |
| **Hoppers** | Pumped mailboxes | Depth is the fill level and overflow is a spill. The backpressure policy is engraved on the hopper. |
| **Pinned cylinder** (music box) | Kronometer | Pins are scheduled timeline entries. Shreds ride the cylinder, and the baton is a single pawl. Predictable signals are cams cut ahead out to their horizon. |
| **Cams** | Animations | One cam per live animation, with its curve and how far it has progressed. |
| **Revolving magazine** | Swapchain / DXGI | One chamber per buffer, rotating through acquire → record → submit → fence → copy → present, with the DWM vblank as a fixed index mark. |
| **Mainspring** | JVM heap | Tension is heap occupancy. A GC releases the spring, and the pause shows as a hitch in every gear at once. |
| **Ledger drawer** | GPU and app resources | Opened and closed counts, bytes, and the owner of each resource. |

There is also a **tape view**: a flat, scrollable timeline of the same events, with each lane as a track and frames as
ruled lines. The clockwork is the bird's-eye view and the tape is the precision instrument. Selecting something in
either one selects it in both.

Three controls work across all views:

- **Stop the clock.** Freeze the view, then step through the rings frame by frame, backward or forward. The target keeps
  running.
- **Problem markers.** A part that breaks a rule glows (see M8). The glow shows from the bird's-eye view, so you don't
  have to drill in to find it.
- **The command menu.** Right-click any datum to get the command behind it (see below).

Gears, cams and beads are natural signed-distance shapes, so the clockwork can be drawn as SDF in a `vexelray-gui`
viewport and does not need a second pipeline.

### The command menu: every datum has its command

Right-clicking any datum, whether a gear, a bead, a row on the tape or a value in a panel, opens a menu:

- **Open in MainFrame**: opens a MainFrame terminal with the `ottermate` command for that datum already typed at the
  prompt, in the right working directory.
- **Copy command**: puts the same command on the clipboard.
- **Copy value**: puts the datum itself on the clipboard.

The same menu opens from the keyboard (the menu key or Shift+F10) on the selected part. A typed command looks like
this:

```
ottermate --port 50845 why 18342
```

Nothing runs until you press Enter. You can edit the command first, run it, and keep going from there in a shell.

- **It makes the GUI and the CLI one instrument.** The Inspector is the GUI and `ottermate` is its CLI. The menu
  shows that a fact on screen is a fact you can ask for.
- **It enforces "agent first".** A datum with no command still gets the menu, but its command items are disabled
  and name what is missing (`no verb yet: bus needs the atchung tap`). A gap is visible the moment you ask for it,
  and a test can assert that every datum the Inspector draws maps to a command.
- **Why a menu rather than an icon on each datum.** A menu adds nothing to the picture, so it works on clockwork parts
  as well as on panels, and it has room for more than one action. What it gives up is that you can't see at a
  glance that the commands are there. The status bar's hint for the item under the pointer ("right-click: command")
  and the keyboard route make up for that.
- **The command names the target exactly.** It carries the port, and `--window` when the datum belongs to a window
  other than the main one. The port lives only as long as that run, so the command is honest about it rather than
  pretending to be durable. Where a landmark or selector names the thing durably (otter.md), the command uses that
  instead of a ref.
- **The working directory is the target's.** That is where a relative path in a command such as `shot after.png`
  should resolve. Every running app records its directory in its discovery file, and `hello` reports it too
  ([discovery.md](../../vexelray-gui/docs/plans/discovery.md)).

What it needs:

- **MainFrame `--insert-command=<text>`.** A sibling of `--insert-path`, the option Vexplore's *Open MainFrame Here*
  uses. It follows the same contract: the text is typed once the prompt is drawn, it is never run, and it is not typed
  if you start typing first. A command is a command line rather than one quoted name, so the safety rule changes:
  every character must come from a set that the shell treats as inert: letters, digits, space and `-_.=:/\~`. The set
  leaves out `#`, which starts a comment in PowerShell, `,`, which makes an array, `@`, which splats, and every
  quote. If any character falls outside the set, nothing is typed and a notice says why. Commands containing an
  otter selector with quotes are therefore refused until MainFrame can quote per shell, and *Copy command* still
  works for them.
- **`ottermate` reachable from that terminal.** It is not on the `PATH` today. Until MainFrame's stack environment
  ([stack-env.md](../../mainframe/docs/stack-env.md)) is built, the typed command uses `ottermate`'s full path.
- **One window per click, at first.** MainFrame cannot yet open a tab in a window that is already running (it can
  only start another instance), so each click opens a new window. A request to the running MainFrame would fix
  that later, and elektro-Q would be the natural channel for it.

## Upstream work (the spine)

The Inspector is a framework witness, like Vexplore. Gaps it finds go into the owning repo's TODO, and reusable parts
go upstream rather than staying here. Each item below reaches the Inspector through a port verb, and
flight-recorder.md §8 says which verb needs which item. These are what it needs:

| Repo | Addition | Why |
| --- | --- | --- |
| atchung (Probe) | A live sink SPI: a pluggable, allocation-free ring next to the file and stdout sinks | Probe has every span and mark already, but it can only write to a file. |
| atchung (Probe) | A public `Ledger` snapshot, with sizes and handles | Gives a resource inventory without a new registry. |
| atchung | `bus.tap(listener)`, topic enumeration, and per-owner mailbox names | There is no wiretap today, and two trees sharing a topic report as one. |
| kronometer | A read-only snapshot: live shreds, scheduled entries, effects and their graph, running animations | All of these are private today. |
| kronometer | A bounded trace ring, and multiple work listeners | `Trace` is an unbounded list, and `onWork` has a single slot that freezes the app if a profiler takes it. |
| kronometer | `horizons()` and `segmentBudget()` | The docs describe them, but they are not in the code. |
| vexelray-gui | `LoopCounters` (frame ring, park kinds, wake reasons) | This is `docs/plans/meter.md`. Fold that plan in rather than building it twice. |
| vexelray-gui | `GuiApp` publishes frame, resize, present and rebuild events | Today only `VulkanEngine` publishes `EngineEvents`. |
| vexelray / supirvast | GPU timestamp query pools, `VK_EXT_debug_utils` names, a validation-message listener, swapchain mode and image-count query | Today GPU time is inferred from the submit→fence latency, and validation messages only go to stdout. |
| vexelray-present-dxgi / dxgi-bridge | Expose the shared fence values per frame, plus DXGI present statistics | Gives the magazine its real positions. |
| vexelray-framework | The processor emitting the `@Subscribe`/`@Publishes` graph as generated data | Threading rule T4.5 notes the graph exists only inside the processor. It is the blueprint for the gear train. |
| vexelray-gui | Discovery ([discovery.md](../../vexelray-gui/docs/plans/discovery.md)): one file per running app in `~/.vexelray/automation/`, kept honest by an OS lock, and a `hello` verb reporting the protocol version and which domains this build and run offer | The attach picker lists running apps without launching them. Panels and menu items grey out with a reason before asking. The command menu gets its working directory. Agents use `ottermate list`. |
| mainframe | `--insert-command=<text>`, typed at the prompt and never run | The command menu's *Open in MainFrame*. A sibling of `--insert-path`, with a character-set safety rule. |

## Milestones

Each milestone ends with something you can watch, or something an agent can ask. M0 is mostly work in other
repositories. From M3 on, each milestone lands its port verb first, so an agent can ask the question before the
Inspector can draw the answer.

### M0 — The debug port

Steps 1–5 of [flight-recorder.md](../../vexelray-gui/docs/plans/flight-recorder.md) §9:

1. The probe live sink, in atchung.
2. `LoopCounters` and the frame counter, in vexelray-gui-core.
3. The recorder and its first verbs: `recorder`, `rows`, `gaps`, `frames`, `why`.
4. Many observers, one hand.
5. The stream: first the shared sender and the text form (5a), then the elektro-Q link module and the three elektro-Q
   fixes it needs (5b).

**M0 landed on 2026-10-17**: all five steps and discovery. The Inspector's end of the link is
`dev.vexelray.gui.inspector.link.LinkClient` in `vexelray-gui-inspector-link`. It connects through a discovery file,
then opens the stream and sends queries. Every framework app in a debug edition, and the gui demo, starts the link
beside its socket.

Discovery ([discovery.md](../../vexelray-gui/docs/plans/discovery.md)) is in M0 too. It can land any time after
step 4: the per-app file, `hello` with protocol 1.0, and `ottermate list`, `--app` and `--pid`.

Done means an agent can run `ottermate list`, pick any running VexelRay app, run `ottermate --app <name> why
<frame>`, and get an answer it can check. A second connection can stream that app's rows while the first one drives
it. All of this works before the Inspector draws anything.

### M1 — First light

- The Inspector app skeleton. It attaches through a picker that watches the discovery folder, by launching
  (`inspector --launch <command>`, like `ottermate --launch`), or by port. It reads the typed stream through
  `LinkClient`, as a second row source beside `--replay`: the link's `Row` maps field for field onto the
  Inspector's rows.
- On screen: a balance wheel that ticks in step with the target's frames, plus frame time and park reason.
- The recorder's own cost is shown from day one, so its overhead is measured, not assumed.

**Mostly landed 2026-10-17.** The picker over the discovery folder, `--attach=<app|pid:n>`, and the live source over
`LinkClient` all work. Choosing the gui demo set the wheel ticking on its real frame numbers, and the picker came back
when the demo was killed. Two parts are still open (docs/TODO.md): attaching by launching, and the recorder's own
cost.

### M2 — The escapement

- The full main-loop breakdown: frame stages, dirty flags (layout, geometry, draw), wake reasons, park budget against
  the actual park, stalls and hook overruns.
- The tape view, and stop-the-clock with ring scrubbing.
- Port: the rest of meter.md (`meter`, `wake`, `focus loop`, and CPU per thread).
- The command menu, on the escapement first. It needs discovery (for the working directory) and MainFrame's
  `--insert-command`. Every later milestone gives its data the menu as that data lands.

### M3 — Messages

- The bus topology, built from the processor graph and confirmed by the live tap.
- Beads moving on conveyors; mailbox depth, drops and batch sizes.
- Per-second rollups and a storm detector.
- Click a bead to read its payload, via the intrabuf text format or `toString`.
- Trace one input end to end: tactroller → `InputPublisher` → `InputDispatcher` → route → handler → mutation → frame.
- Port: `bus`, after the atchung topic registry and tap land.

### M4 — Time

- The pinned cylinder: live shreds and their states (the SPORK…END trace), scheduled entries, effects and their
  dependencies, predictions and horizons.
- Overruns, slip and slack, and `whyBusy()` stated in plain words.
- Animations as cams.
- Port: `kron`, after the kronometer snapshot lands.

### M5 — GPU and present

- The magazine for both hosts: `WindowedPresenter` (fences, acquire, rebuild) and `DxgiPresenter` (latency waitable,
  shared fence timeline, copy and present).
- DWM vblank alignment, missed vblanks, and resize and rebuild events.
- GPU timestamps per pass and per technique.
- The resource ledger with bytes and owners, and validation messages linked to the frame they occurred in.
- Frame thumbnails through `readFrame`, taken on demand. `shot` already does this.
- Port: `gpu` and `ledger`.

### M6 — Threads and the JVM

- Every lane: busy, parked, stalled, and CPU time per thread.
- GC events and pause times, heap occupancy, and allocation rate.
- See the open questions for native-image support.
- Port: `threads`, then `heap` after the native-image spike.

### M7 — The tree and screen updates

- The retained tree with a heatmap of mutations, and which nodes changed in each frame.
- Layout and draw-dirty regions.
- Paint flashing: why this frame was drawn at all.
- Port: `tree` already exists. Per-frame node changes come from the mutation rows in the recorder.

### M8 — Diagnosis, recording and replay

Rules that make problems glow. They run in the port as `diagnose`, so an agent gets as text the same findings that
the Inspector shows as glowing parts:

- frame over budget
- a wake that drew nothing (a wasted frame)
- not idling when the app should be idle
- a mailbox that keeps growing, or a message storm
- a main-thread stall
- a GC during an animation
- a missed vblank
- a resource leak (the gap between opened and closed keeps growing)

Recording and replay:

- Save a session to a file and open it later. A recording is saved as a probe CSV, with the frame stamp column, so
  existing captures open too.
- Scripted capture through `ottermate`, so CI runs can attach recordings to failures.

## Principles

- **Zero cost when off.** The release edition has no automation module, and `Probe.ON` stays a static final.
- **Agent first.** Every fact the Inspector draws can also be asked through a port verb, with a fixed reply shape.
  If an agent cannot ask it, the Inspector does not draw it.
- **Bounded cost when on, and visible.** The Inspector shows what it costs.
- **Never block the target.** Rings drop data and count what they dropped.
- **Nothing allocates or logs in frame hooks.** `FrameHooks.run` already forbids both.
- **Every visual element means something.** If a gear moves, a thread did work.

## Open questions

1. **Attach to an already-running app.** `Probe.ON` is decided at class load, so v1 attaches only at launch. Attaching
   to a running app would need Probe to be switchable at runtime.
2. **GC and heap data under native-image.** Which `GarbageCollectorMXBean` notifications and JFR events work in a
   GraalVM native binary? This needs a spike before M6 is scoped.
3. **Popups and dialogs run on separate buses.** The port has to discover them per window.
4. **The overlap with `otter.md`.** The meter.md overlap is settled: flight-recorder.md folds it in. What remains is how
   the hand interacts with otter.md's long-lived sessions (flight-recorder.md Q4).

## First slice

Build M0, then M1, then the escapement half of M2. M0 is useful on its own: an agent can explain a slow frame before
the Inspector exists. M1 then runs the whole path from a hook in the target to an animated gear, which proves the
architecture before the wide work begins.
