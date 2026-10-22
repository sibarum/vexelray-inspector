# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**VexelRay Inspector** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the [retrospective](retrospective.md) at the end of the milestone has a candidate rather than a
complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

## FN-1 · No lane for a long-lived reader of an outside source 🔬

The Inspector's rows come from outside the process: a file played at its recorded pace today, the automation
socket's `stream` verb next. Either way one thread spends its life blocked (parked until the next row is due, or
in a socket read) and delivers rows one at a time, in order. threading.md's lanes are a closed list (T1.1), and none
fits: the offload lane is bounded and for finite work, so a reader that never returns would hold one of its threads
for good; a handler is per message; the timeline must not block on I/O. A `@Component` with its own thread is the
nearest, but it is an actor with mailboxes, and this has nothing to receive.

So `ReplaySource` starts a named platform thread of its own (`inspector-replay`, daemon, interrupted and joined on
close, since the framework closes `AutoCloseable` parts). It works, and it is outside the model the framework can
check.

**What the framework could do:** a *source* lane, or a `@Component` shape for a producer that only emits: one
platform thread, named in the wiring, started after its consumers and stopped before them (T6.1, T6.3). Every client
of the flight recorder's stream will want the same.

**Seen again, 2026-10-19:** `LaunchSource` (`--launch`) needed two more such threads: `inspector-launch` waits for
the child's discovery file, and `inspector-launch-output` reads the child's output for as long as the child runs. It
also keeps its own shutdown hook beside the disposer, so the child is stopped even when the Inspector is not closed
in order. `ottermate`'s `Launch` holds the same code; a launcher that starts, watches and stops a child application
is a second candidate component here.

## FN-3 · A setting cannot take the rest of the command line 🔬

`--launch` wants a command line as its value: `Inspector --launch mvn.cmd compile exec:exec -Dautomation=0`, as
`ottermate --launch` takes it. Settings are only `--key=value`. A quoted `--launch="mvn.cmd compile ..."` works from
a shell, but not through `-Dapp.args`, which the template's pom splits on white space before the application sees it,
so the quotes cannot hold the words together.

Worked around in `Inspector.main`: before `VexelApplication.run`, `LaunchSource.fold` turns a bare `--launch` and
every word after it into one `--launch=<command>`, quoting a word that has white space in it.

**What the framework could do:** let a `@Setting` declare that it takes the rest of the line (`rest = true`), and
fold it in the shell's own argument parser, so the misspelled-flag check sees the folded form and no application
edits its `args`.

## FN-2 · A `Sketch.line`'s round caps are inside its ends, so a short thick line is a bead 🔬

drawing.md §3 calls a line "round-capped", and says a polyline's joints look joined because of the caps. Measured:
`Canvas.strokeLine` draws a rounded box whose half-length is half the distance between the two points and whose
corner radius is half the thickness, so the rounding is *inside* the segment and nothing extends past either
point. Two consequences:

- A segment shorter than it is thick does not draw as a capsule but as a lozenge no longer than the segment. Six
  short segments of a tapered bridge drew as six beads with gaps between them.
- A polyline's joints are only joined while the line is thin relative to its segments. A thick polyline round a
  tight curve will show notches at every joint.

Worked around in `Parts.cock`: the taper is lines that all start at the foot, each longer and narrower than the
last, so no line is shorter than its thickness.

**What the framework could do:** extend the box by the radius at both ends (a true capsule, as SVG's
`stroke-linecap="round"` is, which is also what `SvgSink` would have to match), or document that caps are inside
and say so on `Sketch.line` and `polyline`.

## FN-4 · A driven run does not probe the bus 🔬

The Inspector's bus conveyors read the `bus` lane: one span per publish, and pumped mailboxes' depth, drops and
batches (atchung `Atchung.publish`, `PumpedReg`, `Pump`). A launch with `--automation` turns the probe on, but with
`frame,input,layout,app` only (atchung `LogConfig`, `LogConfigTest.aDrivenRunIsTheLoudestAndTurnsTheProbeOn`), so
an application attached to from the picker sends no bus rows at all. Measured on the calculator demo: none with
`-Dautomation=0`, 191 in 0.45 s with `-Dprobe=all -Dprobe.format=csv` added.

Worked around twice. `--launch` starts its child with `PROBE=frame,input,layout,app,bus` and `PROBE_FORMAT=csv` in
the child's environment, unless the caller set `PROBE` (`LaunchSource.probe`). An application attached to from the
picker can't be changed, so the bus panel names the flags when a run has 120 frames and no bus rows. One more snag
found on the way: `-Dprobe=all` on its own turns off the driven run's CSV tracing, and an untraced probe gives the
ring nothing but slow rows, not even frames. Both flags are needed, and two flags do not fit in one `-Dapp.jvmArgs`
word through a launch, which is why the launch uses the environment.

**What the framework could do:** add `bus` to a driven run's lanes. A publish row is one span on the publisher's
thread, which the flight recorder already budgets for; the cost is worth measuring against the gui demo before
deciding, and the default is atchung's call.

## FN-5 · Every colour change fades, and a node cannot say it should not 🔬

A bead on the bus is gold when the main thread published its message and blued steel when another thread did. The
beads are a fixed pool of nodes, so a node changes colour when it is given to a message from another thread. The
shell installs a `PaintMotion` (`Fades`), which fades every background, border and text colour change on every node.
That's right for a hover, and wrong here: a steel bead started its crossing as a gold one fading to steel over its
first ~100 ms, so a storm from a worker thread showed its newest beads as a gold-to-steel gradient.

Worked around in `BusView`: each bead holds two dots, one of each colour, and shows one by opacity, which is a visual
transform and does not fade. Twice the nodes for the beads.

**What the framework could do:** let a node opt out of paint motion (`node.paintMotion(false)`, or a `Role` that
cuts), for a part whose colour is data rather than state.

## FN-6 · Nothing at runtime says who sent a message or who received it 🔬

The bus circuit (docs/bus-circuit.md) draws components as chips and topics as traces from publishers to mailboxes,
and each message as an electron along them. Only declared wiring is drawn. Measured on 2026-10-19, none of it reaches
a running application:

- **The graph stays in the processor.** `Channels` builds the declared component → topic → mailbox graph to check
  T4.1, T4.4 and T4.7, and the generated wiring keeps only the mailbox registrations. threading.md T4.5: exposing it
  "is additive and not built". `@Publishes` on a non-component (a `Ui`) is dropped from the edges.
- **A subscription has no name.** atchung's `Reg`, `PumpedReg` and `Pump.subscribe` carry no owner. There is no
  topic or subscriber enumeration and no tap. A `Placement` is named for its lane, which several components share.
- **The probe names a publish's topic and thread, and no receiver.** Pumped mailboxes report depth, drops and batches
  keyed by topic alone; inline and async deliveries write nothing.

**What the stack is doing about it:** the contract in docs/bus-circuit.md. The processor emits the graph (`BUS_GRAPH`,
handed to `BusGraph.declare`), atchung names pumped mailboxes and writes `enqueue`, `deliver` and `drop` rows for
named ones, and vexelray-gui serves the graph as `bus graph`. Landed 2026-10-19: atchung `9f17ce2`, vexelray-gui `6a1a52e`, vexelray-framework `0e8f79f` and `c1140fa`. The
Inspector asks `bus graph` over the link when a run begins; a replay uses `--bus.graph=<file>`.

## FN-7 · The bus cannot say who receives a message 🔬

The component graph (docs/component-graph.md) draws an arrow from the part that sent a message to each part that
received it. Surveyed on 2026-10-21, the receivers are out of reach for all but one kind of subscription:

- **A subscription has no owner.** atchung's `Reg`, `InlineReg`, `AsyncReg` and `PumpedReg` carry no name and no
  part. `Atchung.registry` is private; the only public question is `subscriberCount(topic)`. There is no list of
  topics or subscribers and no tap.
- **Only a named pumped mailbox writes a row per delivery** (`enqueue`, `deliver`, `drop`; FN-6). Those are the
  framework's `@Subscribe` mailboxes. Every subscription the framework wires by hand is unnamed:
  `InputDispatcher`'s and `KronoInput`'s on `tactroller.input`, the reconciler's on `vexelray.gui.mutations`, and
  `Gui`'s on `vexelray.gui.navigate`. Inline and async deliveries write nothing at all.

Worked around in `graph/Receivers`: a stand-in table from topic to receiving part, written from the survey, used
when a publish enqueued to no named mailbox. A topic not in it is shown with `?` as its receiver.

**What atchung could do:** take an owner at subscribe (`subscribe(Owner, topic, ...)`, where an owner is a part:
`host`, `graphics`, `input`, `automation`, `gui`, `app` or `component:<lane>`, plus a name), keep the existing forms
as `Owner.UNKNOWN`, and:

- answer a `bus subscribers` verb over the link and the automation socket, one line per subscription:
  `subscriber <name> part=<part> topic=<topic> type=<payload> mode=inline|async|pumped overflow=<policy> capacity=<n>`;
- write `deliver <name> <count>` for every owned subscription, inline and async included, on the thread that
  delivers, while the probe is on. The named mailboxes' rows do not change.

## FN-8 · A publish names its thread, not its sender 🔬

A publish row is the topic and the publishing thread (`Atchung.java:99`, `Probe.java:385`), and nothing else says who
sent it. The graph reads the sender from the thread and the topic (docs/component-graph.md), which is right for a
component's lane and a handler, and a guess on the main thread: a mutation written by the application's own code there
is counted as the GUI's. Automation is worse off: it publishes the same `InputEvent`s on the same `tactroller.input`
as a real device (`Cursor.java:253`, `Automation.java:697,718,934`), so only its thread (`vexelray-automation-<n>`)
tells the two apart.

Worked around in `graph/Senders`: the thread-and-topic rules in docs/component-graph.md.

**What atchung and the framework could do:** a publish handle per owner (`bus.publisher(Owner)`, the "publish handle
per component" bus-circuit.md §3 left out of version 1), with the generated wiring handing each component and each
`Ui` its own. A publish through a handle writes `sent <name> <topic>` on the bus lane before its span. The automation
server publishes through its own handle, so its input is automation by what the row says.

## FN-9 · The host's window events are not on the bus 🔬

Nothing the operating system tells the application is a message. Surveyed on 2026-10-21: `vexelray-os` does not import
atchung, and `NativeWindow` is polled. Close is `pumpEvents()` returning false (`GuiWindow.java:263`). Resize is
the width and height read each frame and handed to `gui.frame(w, h)` (`GuiWindow.java:303,488`), with live resize on
a callback (`setFrameSink`). Focus for the loop's policy, and minimise, maximise and fullscreen, are polled
(`GuiApp.java:1632`, `GuiWindow.java:271,340`). Display scale is not reported at all (NativeWindow gap E4).
Clipboard is direct calls. No application reads STDIN. The one host fact on the bus is focus, and that arrives as
tactroller input (`InputEvent.FocusChanged`).

Worked around: not at all. The graph draws the Host OS node and its arrow to the GUI dashed, labelled with this
note.

**What vexelray-gui could do:** publish on change, on the loop's thread, from the polls it already makes:
`vexelray.host.resized` (width, height), `vexelray.host.close.requested`, `vexelray.host.focus`, `vexelray.host.state`
(normal, minimised, maximised, fullscreen) and `vexelray.host.scale` once `NativeWindow` reports it. The framework
shell could offer `vexelray.host.stdin` (one message per line), read only while something subscribes. A publish with
no subscribers is a count (`Atchung.java:93`), so an application that listens to none of them pays almost nothing.

## FN-10 · Graphics events are not on the bus in a GUI application 🔬

`EngineEvents` (`vexelray-engine-api/.../EngineEvents.java:61-98`) declares seven topics (run started and ended, frame
started, resized, technique realised and closed, device lost), but they are published only by
`VexelEngine.run(pipeline, bus, cb)`, which only Fathom calls. `GuiApp` and every framework application never publish
them. What the presenters do (present, the DXGI copy and present, fence waits, swapchain rebuilds) is probe rows on the
`gpu` lane (`WindowedPresenter.java:577,620,928`, `DxgiPresenter.java:213`), not messages. Compute results travel
through rings and states, not the bus.

Worked around: not at all. The graph draws the Graphics node and its arrow to the GUI dashed, labelled with this
note.

**What vexelray-gui and vexelray could do:** publish `EngineEvents` on the GUI app's path too, and add
`vexelray.gpu.presented` (frame, latency), `vexelray.gpu.swapchain.rebuilt` and `vexelray.gpu.device.lost` from the
presenters, on their thread. As with FN-9, no subscriber means only a count.

## FN-11 · A handler call is not a message 🔬

The arrow that matters most in an application is the GUI reaching the application's code: a click calling its
handler. The dispatcher runs handlers by `handlerExecutor.execute(...)` (`InputDispatcher.java`, near line 1932) on
the handler pool (`vexel-handler-<n>`), with no bus message and no row. So the graph sees handlers only by what they
send back (mutations from `vexel-handler-<n>`), never by what reached them.

Worked around: the graph draws GUI → App logic dashed, labelled with this note.

**What vexelray-gui could do:** write `call <event> <node>` on the bus lane at the hand-off, on the GUI thread, where
`<event>` is the input's kind (`click`, `key`, `drag`) and `<node>` the target's landmark or id. The handler's own
rows then follow on its thread. If handlers become subscriptions with owners (FN-7), the row is that subscription's
`deliver`.

## FN-12 · An application cannot say what it draws with 🔬

Wanted: the Inspector to say which device the inspected application draws on, its driver, the Vulkan version, how
frames reach the screen (DXGI or a Vulkan swapchain), and above all whether it is a real GPU or a software rasterizer
(llvmpipe, lavapipe, SwiftShader, WARP). Asked as: "idk if its even running with hardware acceleration or not."

`GuiApp` knows all of it at startup and keeps it to itself: the `DeviceSelection` (`GuiApp.java`, near line 340) and
the `PresenterProvider.Backend` are private, and nothing writes them to the log, the discovery file, a probe row or a
verb. The `gpu` verb answers `err not available yet: gpu needs presenter statistics from GuiApp` (`Domains.java`).
Under it, supirvast's `VulkanInstance` reads `VkPhysicalDeviceProperties` whole but makes only the name and
`deviceType` public (`DeviceInfo`); `apiVersion`, `driverVersion` and `vendorID` stay private, and
`VkPhysicalDeviceDriverProperties` is not read at all.

Worked around: the Inspector asks `gpu device` over the link once per run, as it asks `bus graph`, and shows the
reply's reason until there is an answer (`graphics/Graphics`, the status line's right end, and the strip it opens).

**What supirvast and vexelray-gui could do:** supirvast exposes the device's properties (`apiVersion`,
`driverVersion`, `vendorID`, `deviceType`) and, on Vulkan 1.2+, `driverName` and `driverInfo`. `GuiApp` keeps what it
chose and answers `gpu device` on the automation socket and the link with `ok gpu device` and one `key=value` per line,
the numbers undecoded so every client decodes them one way:

    device=<deviceName>  type=<deviceType>  vendor=<vendorID>  driver=<driverVersion>
    driver.name=<driverName>  driver.info=<driverInfo>  api=<apiVersion>
    present=dxgi|swapchain  present.mode=<fifo|mailbox|immediate>  images=<n>  validation=on|off

It is a startup fact, so the reply needs no recorder; it could equally go in the discovery file (`gpu.*`) beside the
build facts, which would show it in the picker before attaching. Every application wants this line in its log too.

## FN-13 · A segment's options cannot be named for automation 🔬

Wanted: an `ottermate` check that clicks the tape's *1 min* and *5 min* by name, as every control a script reaches
gets a landmark (CLAUDE.md). `Segment` makes each option's node inside `option(label, value)` and keeps it private,
and `Node` has no way to reach a child, so an option cannot be given a landmark, and `ottermate` has no click by text.

Worked around in the check only: `click tape.seconds` presses the middle option, and `find 5 min` gives a node number
that is stable between identical runs, which the next run clicks. Nothing in the application works around it.

**What vexelray-gui could do:** `Segment.option(label, value, landmark)`, or a `cell(value)` that returns the option's
node so the caller can name it, as `Button.node()` does for a button. `Tabs` and `Select` are worth the same look.
