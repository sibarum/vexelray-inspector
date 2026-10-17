# Inspector roadmap

The Inspector is DevTools for VexelRay applications. It shows every working part of a running app in one view: messages,
event storms, timers, animations, screen updates, devices, swapchain buffers, idle threads and GCs. The aim is that a
problem is obvious at a glance instead of something to guess at. It is drawn as live, animated clockwork: precise,
mechanical and tangible.

This document says what the Inspector is made of, what the stack has to grow before it can work, and the order to build
it in. Proposed 2026-10-17.

## Shape

It has three parts.

- **The agent** (`vexelray-inspector-agent`) runs inside the target app. It is an `InspectorStarter` that mirrors
  `AutomationStarter`: a `@Configuration` with `@Default @Provides Inspector inspector(Shell shell)`, so the wiring
  places it in ATTACH. From the `Shell` it reaches the bus, Gui, Kron, lanes, windows and `hooks()`. It only collects
  data: it writes into bounded rings and never blocks the target. When a ring is full it drops data and counts what it
  dropped. It ships only in the debug edition. Release builds drop it the same way they drop automation.
- **The wire** is a separate streaming socket, opted into with `--inspector=on|<port>|0`. When it binds, the agent prints
  `inspector: localhost:<port>`, the same way automation prints its port. Frames on it are intrabuf records.
- **The Inspector** is itself a VexelRay app, built from the `vexel-desktop` template, and runs out of process. Running
  apart means it disturbs the target as little as possible, outlives a target crash, and can inspect itself.

Two values tie everything together:

- **One clock:** the target's monotonic nanoseconds, the time base Probe already uses.
- **One correlation key:** the frame number. Every event on every lane is stamped with the frame it happened in. That
  stamp is what lets the clockwork line up.

### Why a new socket, not more automation verbs

The automation socket is request/response, accepts one client, and replies in prose lines. The Inspector needs pushed
events at frame rate, and it has to run at the same time as `ottermate`. Keeping the two sockets apart leaves
automation's text contract alone. Automation can still gain one verb, `inspector`, that reports the stream's port for
clients that find automation first.

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

Two controls work across all views:

- **Stop the clock.** Freeze the view, then step through the rings frame by frame, backward or forward. The target keeps
  running.
- **Problem markers.** A part that breaks a rule glows (see M8). The glow shows from the bird's-eye view, so you don't
  have to drill in to find it.

Gears, cams and beads are natural signed-distance shapes, so the clockwork can be drawn as SDF in a `vexelray-gui`
viewport and does not need a second pipeline.

## Upstream work (the spine)

The Inspector is a framework witness, like Vexplore. Gaps it finds go into the owning repo's TODO, and reusable parts
go upstream rather than staying here. These are what it needs:

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
| vexelray-framework | An `inspector` entry in `Launch.FRAMEWORK_KEYS`, and the processor emitting the `@Subscribe`/`@Publishes` graph as generated data | Threading rule T4.5 notes the graph exists only inside the processor. It is the blueprint for the gear train. |

## Milestones

Each milestone ends with something you can watch.

### M0 — The spine

The upstream hooks M1–M2 need: the Probe live sink, `LoopCounters`, and the framework `inspector` key. Everything else
in the upstream table is pulled in by the milestone that needs it.

### M1 — First light

- The agent starter, the wire protocol (handshake, schema version, intrabuf frames), and the Inspector app skeleton.
- Attach by launching (`inspector --launch <command>`, like `ottermate --launch`) or by port.
- On screen: a balance wheel that ticks in step with the target's frames, plus frame time and park reason.
- The agent's own cost per frame is shown from day one, so its overhead is measured, not assumed.

### M2 — The escapement

- The full main-loop breakdown: frame stages, dirty flags (layout, geometry, draw), wake reasons, park budget against
  the actual park, stalls and hook overruns.
- The tape view, and stop-the-clock with ring scrubbing.

### M3 — Messages

- The bus topology, built from the processor graph and confirmed by the live tap.
- Beads moving on conveyors; mailbox depth, drops and batch sizes.
- Per-second rollups and a storm detector.
- Click a bead to read its payload, via the intrabuf text format or `toString`.
- Trace one input end to end: tactroller → `InputPublisher` → `InputDispatcher` → route → handler → mutation → frame.

### M4 — Time

- The pinned cylinder: live shreds and their states (the SPORK…END trace), scheduled entries, effects and their
  dependencies, predictions and horizons.
- Overruns, slip and slack, and `whyBusy()` stated in plain words.
- Animations as cams.

### M5 — GPU and present

- The magazine for both hosts: `WindowedPresenter` (fences, acquire, rebuild) and `DxgiPresenter` (latency waitable,
  shared fence timeline, copy and present).
- DWM vblank alignment, missed vblanks, and resize and rebuild events.
- GPU timestamps per pass and per technique.
- The resource ledger with bytes and owners, and validation messages linked to the frame they occurred in.
- Frame thumbnails through `readFrame`, taken on demand.

### M6 — Threads and the JVM

- Every lane: busy, parked, stalled, and CPU time per thread.
- GC events and pause times, heap occupancy, and allocation rate.
- See the open questions for native-image support.

### M7 — The tree and screen updates

- The retained tree with a heatmap of mutations, and which nodes changed in each frame.
- Layout and draw-dirty regions.
- Paint flashing: why this frame was drawn at all.

### M8 — Diagnosis, recording and replay

Rules that make problems glow:

- frame over budget
- a wake that drew nothing (a wasted frame)
- not idling when the app should be idle
- a mailbox that keeps growing, or a message storm
- a main-thread stall
- a GC during an animation
- a missed vblank
- a resource leak (the gap between opened and closed keeps growing)

Recording and replay:

- Save a session to a file and open it later. The importer also reads Probe CSV, so existing captures work.
- Scripted capture through `ottermate`, so CI runs can attach recordings to failures.

## Principles

- **Zero cost when off.** The release edition has no agent, and `Probe.ON` stays a static final.
- **Bounded cost when on, and visible.** The Inspector shows what it costs.
- **Never block the target.** Rings drop data and count what they dropped.
- **Nothing allocates or logs in frame hooks.** `FrameHooks.run` already forbids both.
- **Every visual element means something.** If a gear moves, a thread did work.

## Open questions

1. **Attach to an already-running app.** `Probe.ON` is decided at class load, so v1 attaches only at launch. Attaching
   to a running app would need Probe to be switchable at runtime.
2. **GC and heap data under native-image.** Which `GarbageCollectorMXBean` notifications and JFR events work in a
   GraalVM native binary? This needs a spike before M6 is scoped.
3. **Popups and dialogs run on separate buses.** The agent has to discover them per window.
4. **The overlap with `meter.md` and `otter.md`.** Agree on one owner for `LoopCounters` and the look/readout verbs.

## First slice

Build M0 (the Probe live sink and `LoopCounters`), M1, and the escapement half of M2. That slice runs the whole path
from a hook in the target to an animated gear in the Inspector, so it proves the architecture before the wide work
begins.
