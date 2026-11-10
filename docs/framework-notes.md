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

## FN-1 · A frame's present was logical time, which nothing outside the process shares 🔬 (fixed upstream)

The live timeline places another process's rows against the present. The frame contract's `Frame.presentNanos` was
the tree's clock, which in a framework application is krono's logical time: it counts from its own zero and falls
behind after a stall, so it cannot be set beside anything stamped elsewhere. Fixed in vexelray-gui (`07dc481`, ruled
2026-10-30): `Frame.monoNanos` carries the same moment on `System.nanoTime()`'s clock. Due times stay on the tree clock.

## FN-2 · A run's rows counted from a zero the link did not say 🔬 (fixed upstream)

A probe row's `t_mono_ns` is `System.nanoTime()` less the probe's own start, and that start was not on the link, so
the Inspector could not put a row on any clock it shares. Fixed upstream (ruled 2026-10-30): atchung's
`Probe.originNanos` (`bb36290`), and the link's `StreamOpened.originNanos` since minor 1 (vexelray-gui `efa63e2`).
`LinkRows` adds it as a row crosses, and refuses a run too old to say it, rather than guess.

## FN-3 · `settle` never succeeds while a part is following the present 🔬

`ottermate settle` waits until no frame is owed. A live timeline owes every frame by design, so `settle` times out
("frame still owed") for as long as a run is attached, though nothing is wrong. The check script waits on a landmark
instead (`await status.run Inspecting`); since R1 slice 2, a script can also press Space to pause, after which
`settle` succeeds. Every application with a part that follows the present (a video, a live
chart) will hit this: `settle` could treat an asked half that is due every frame as continuous motion, as it already
treats a widget's, and say so in its reply rather than time out.

## FN-5 · A view's told half cannot read the present 🔬

The view decides, when the bench's `Timeline` arrives or a key is pressed, where its lane will come to rest and when a
handover starts (design.md 9.8): that needs the present, on the same clock the asked half reads as `Frame.monoNanos`.
`Gui.monoNanos()` is package-private, so the view reads `System.nanoTime()`, which is what the default clock returns.
It costs the stepped clocks a test would hand the `Gui`: they reach the asked half and not the told half. A public
`Gui.monoNanos()` (or the moment a message was taken, handed to a `@Subscribe`) would close it; any part that starts a
motion from a message will want it.

## FN-7 · A view cannot hear where the pointer is over a node 🔬 (fixed upstream)

Fixed in vexelray-gui (2026-10-30): `NodeEvent.POINTER` hears a `PointerEvent` (the pointer in client space, the
node's box, `localX`/`localY`, and `over`) on every move over the node or a descendant, folded to the newest per
frame, and once with `over` false when it leaves. Widget libraries have the same as `Seams.onPointer`.

Asked for by the author: a hover effect on each pass in the lane. A view hears a node's `CLICK` (with where it
landed), its `STATE` (normal, hover, pressed) and `DRAG`, but not where the pointer is while it moves over the node:
only the input dispatcher reads `PointerMoved`. A pass is a mark in a picture, not a node, so `STATE` cannot say which
one; and a node per pass (thousands) is not the answer. Not worked around: subscribing a view to the raw input topic
would bypass the dispatcher's hit-testing. Clicking works, since a click carries its position. A `NodeEvent.POINTER`
(the pointer's position while it is over the node, folded to the newest each frame, and leaving) would let any
picture-drawn chart, plot or timeline highlight what is under the pointer. Hover stays a colour change only, per the
stack's rule that nothing appears, moves or grows on hover.

## FN-6 · A node slid by `Drawn.offset` shows nothing beyond its own box 🔬

The passes lane slides one strip node by `Drawn.offset` for a glide. A node's picture is clipped to the node's own box
where it is drawn (`TreeRenderer.emitPicture`), so a strip as wide as the lane left the edge it slid away from empty
for the length of the glide, and the marks there appeared at once when it landed: seen by the author as marks
blinking at the left edge on a step forward. Worked around by making the strip fifteen lanes wide
(`View.SLIDE_LANES` each side) and shifting it back at rest, with the lane `clip(true).scroll(false, false)`: clipped
only, or the overflow gets a scrollbar (the framework's own diag said so, usefully). `Drawn.offset`'s javadoc says
nothing of this. Every part that slides a picture through a view (a scrolling chart, a ticker) will hit it: the
javadoc could say it, or a picture could be drawn unclipped inside a clipping parent, its marks culled by the
parent's clip rather than the node's box.

## FN-8 · Under `--logic=main`, a logic tick cannot be told from the widgets' 🔬 (fixed upstream)

The timeline's Logic lane is atchung's `tick` zone (`Tick.java`, `Lane.BUS`), and the GUI's widget tick writes the same
zone on `main`. On a logic lane the thread tells them apart (`vexel-logic` against `main`), so `Model` takes every
`tick` row not on `main`. Under `--logic=main` both are on `main`, with nothing else in the row to separate them (the
link's `Row` has no cause, and the zone's detail is only its length), so the Logic lane and the readouts' ticks are
empty there rather than wrong. Seen in a traced run (2026-10-30): `vexel-logic` and `main` each write about 100 `tick`
rows a second. A tick's own row, numbered and named for its `Tick` (design.md 7½, the row per tick), would close it; so
would the zone naming its tick (`tick logic`, `tick widgets`). The mode itself is also not said (design.md section 7).
Fixed upstream 2026-11-02 (R7), by FN-16's fix: the logic's `tick.ran` row names its tick, and `Model` holds a tick
zone on `main` until that row says whose it is, so the Logic lane is the logic's in both modes.

## FN-9 · Automation alone records no ticks and no GPU, and the template's pom dropped `-Dprobe` 🔬 (fixed upstream)

An application started with `--automation=0` and no `-Dprobe` records the `input`, `frame`, `layout` and `app` lanes
only (its startup line says so), so attached to one, the Logic, Drawing and GPU lanes stay empty with no word of why.
Worse, found 2026-10-31: the template's pom passes `-Dautomation` to the forked JVM by name and nothing else, so
`mvn exec:exec -Dautomation=0 -Dprobe=all` ran the app with automation's lanes only, and every "traced" run checked
in this file and in design.md before then (the author's too) had no bus, state, draw or GPU rows. Fixed, ruled by the
author: the template's pom (vexelray-framework) and this one pass `-Dprobe` and `-Dprobe.format` by name, blank
meaning unset; the link's `StreamOpened` says which lanes the run records (`recorded`, minor 3), and a lane the run
does not record has its name dimmed and the status line names it with how to start one that does (`status.lanes`).
Still a trap for any other pom that forks the JVM: a `-D` the forked command line does not name is silently dropped.

**Replaced by one setting 2026-10-31**, ruled by the author: four more traps were found under this one. The plug's
*Restart in debug mode* took the pom's blank `-Dprobe=` for a chosen probe and armed nothing, so Rasterware restarted with
automation's four lanes. Its own lanes left out `gpu` and `state` anyway. `-Dprobe=all` without `-Dprobe.format=csv`
traced nothing, and only traced rows reach the link. And `VEXELRAY_AUTOMATION` opened the sockets in a run whose probe
was off. Now the diagnostics socket asked for at launch is the whole setting: atchung's `LogConfig.inspected` makes
such a run record every lane, traced, and no probe setting narrows it (the restart passes nothing about the probe, and
the shell reads the environment as the Driver does). The link says its `InspectorLink.VERSION` in `StreamOpened`
(minor 4), and a run of another version is shown under a caution banner rather than trusted.

## FN-10 · A message's row names no sender, no receiver and no transport 🔬

A message published on the bus writes one row on the `bus` lane whose kind is its type and whose thread is the one
that published it. That is all the details panel can say of it: not which component sent it (the cause would link it,
and the recorder drops the cause, design.md section 7), not who received it (bus graph version 1 lists `@Subscribe`
mailboxes only, and most 0.2 receivers are `Inbox`es), and not how it travelled (state or edge topic, which mailbox,
its lane and capacity), which version 1 does not say either. Worked around by saying so in the panel: From is the
thread, and To and Transport say "not recorded yet". Bus graph version 2 (design.md 7½) and the kept cause would fill
all three; the row naming its topic would let the Inspector match it to the graph.

**Sender and receiver fixed upstream 2026-10-31**, ruled by the author: the cause is kept (FN-12), and atchung's
`Inbox` writes a `send` row (`<topic> from <inbox> <hops>`) when a component's step sends and a `receive` row
(`<topic> to <inbox> <hops>`) when a tick delivers, each in the message's own cause, so every hop between a source and
the GUI is a row. **Receiving method fixed upstream the same day**: atchung's `Inbox.subscribe` takes a mailbox name
and its `receive` row says it after the inbox's (`<topic> to widgets dev.sibarum.inspector.View#timeline <hops>`);
`Gui.mailbox`, the framework's `Views.mailbox` and `Inboxes.mailbox` pass it, and the processor names every
`@Subscribe` mailbox by its bus-graph name, `class#method`. A ledger's steps (`Ledger.on`) are still unnamed, so a
delivery to a component that hears through its ledger says the component only. Still not recorded: how a message
travelled (bus graph 2).

## FN-4 · No verb closes an application 🔬

A run started outside `--launch` is closed by clicking where the title bar draws its close button
(`click 1017,16`): the caption buttons are not nodes, so `find close` finds nothing and a script has to know pixel
positions. A `close` verb, or landmarks on the caption buttons, would make it a name.


## FN-11 · A disabled button keeps the focus, and with it Space 🔬

An `IconButton` (and `Button`) claims Enter and Space while it is focused, and a click focuses it. Disabling it takes
it out of the focus order but leaves the focus where it is, so a button disabled by its own press (the Inspector's
follow button, which is pressable only while the cursor is stopped) goes on swallowing Space, which the Inspector
binds to pause. Seen with `ottermate`: after a click on the button, Space did nothing and the tree showed the
disabled button `focused ring`. Worked around by never letting the button take the focus (`Ui.canFollow`); End is its
key. The widget could hand the focus on when it is disabled while focused, and a disabled button should not claim
keys at all.
Met again in R4: a click on the rows panel's table focused its body (`rowgroup ... focused ring`), so it is worked
around the same way (`gui.focusable(rows.rows().node(), false)`, in `Ui`). Whether a focused list takes Space and the
arrows ahead of a declared shortcut was not tested: Down still stepped by rows with the list focused. A control an
application means for the pointer alone could say so once, rather than each application unfocusing every one.

## FN-12 · The link dropped every row's cause, and a late reader could not name a cause's source 🔬 (fixed upstream)

The probe's CSV has a `cause` column: what set a row's work off, a source (`t03`) and its firing. The details panel
wanted it, to say an event was set off by an automation command, an input device, a timer or I/O. The live sink was
given it (atchung's `LiveSink.row(…, cause)`), but the recorder took only the form without it, so its ring, its
`Rows` and the link's `Row` never had one. And the run names its sources once, in `cause.source` rows written at
start-up, which an Inspector that attaches later, or a ring that has moved on, never sees. Fixed upstream 2026-10-31,
ruled by the author ("populated with as much helpful information as possible from within the framework"):
atchung-probe's `Cause.sources()` lists every source registered; vexelray-gui-recorder keeps each row's cause
(`Rows.cause`); the link's `Row` carries it as the CSV writes it (`cause`, minor 1) and `StreamOpened` hands over the
source table (`sources`, minor 2), both readable from an older writer as null. Still not recorded, and the next
things a troubleshooter asks: a message's receivers (FN-10), and which keys a pass's input edges were.

## FN-13 · No option takes the rest of the command line 🔬

The Inspector's `--launch <command>` was to take everything after it as the command to start, as `ottermate --launch`
does, so `inspector --launch mvn.cmd -o exec:exec -Dautomation=0` would work as typed. The framework's `Launch.parse`
has no such option: every `-x` or `--x` after it is parsed as the Inspector's own and refused as unknown, and the
words left over go to `rest` with no say in where they came from. Worked around with one setting holding the whole
command, `--launch="<command>"`, which `Child.words` splits on spaces outside double quotes. It costs the quoting: from
`mvn exec:exec` the value has to survive `${app.args}` inside the pom's `commandlineArgs` as well, so it was only tried
by starting the Inspector with `java` directly. The framework could let an application declare one key whose value is
the rest of the line (`@Setting(value = "launch", rest = true)`), parsed before the refusal of unknown options, and
`ottermate` could use the same rule.

## FN-14 · A write from outside a tick names no sender, only its thread 🔬

The flows panel counts what each part wrote on each topic. A component's write inside a tick is a `send` row that
names it (`<topic> from <inbox> <hops>`, FN-10), but a write from outside one, a source's `Sender.outside`, a job's
reply, a timer's beat, a widget's message, is only the publish row, whose kind is the topic and whose thread is the
one that published it. So a topic two sources declare (the Inspector's own `Rows`: `LinkRows` and `RecordingRows`)
could not say which wrote it. Worked around in `Model.writesOn`, by the framework's own thread names: a source's is
`vexel-source-<its name in kebab case>`, a destination's `vexel-destination-...`, the job lane's `vexel-job-<n>`, the
timer's `vexel-timer`, a view's the main thread. It holds while those names do, and says nothing when two parts share
a thread kind (two components that take the timer). The publish row could say who published it, as `send` does, when
the sender knows (`Sender.outside` is built for one part, `Sources.sender(shell, "<part>", topic)`).

## FN-15 · What reaches a job, a source or a destination writes no row 🔬 (fixed upstream for jobs)

A tick's delivery to a mailbox or an inbox is a `receive` row, so the flows panel can say how many of a topic's
messages each receiver was handed. A job's request, a source's start and stop, and a destination's open and write are
handed over by the framework (`Jobs`, `Sources`, `Destinations`), which writes no row, so those ends have no count.
Worked around by saying none (`End.UNCOUNTED`, shown as the part's name alone, and no delivered count for a topic
heard only that way) rather than 0, which would claim nothing arrived. The framework could write a `receive` row
there too, naming the part as bus graph 2 does (`<topic> to <part> <hops>`), which would also put those hops on a
cause's path. Fixed upstream for jobs 2026-11-02 (R7, vexelray-framework `0cbb1f4`): `Jobs` writes that `receive` row
as a request reaches a job (its hops 0, since an async subscriber is handed none) and a `job <name>` zone around its
run, so a job's deliveries are counted and its runs are the Jobs lane. A source's start and stop and a destination's
open and write still write none.

## FN-16 · A tick's row says its number but not whose tick it is 🔬 (fixed upstream)

atchung's `tick.ran` row (added for R3) numbers each `Tick`'s runs from one, and a `Tick` has no name. The framework
makes several: the logic lane's (`Logic`), the widgets' (`Gui`), and `Senders`'. Off the main thread that is no
trouble, since the logic's coordinator is the only tick on `vexel-logic`. Under `--logic=main` the logic's ticks and
the widgets' run on `main`, both write `tick #1`, `tick #2`, ... by their own counts, and a reader cannot tell which
tick a number is, the same fact as FN-8 from the other side. Worked around by reading tick numbers only off `main`
(`Model.setOnTick`, `Model.hopsOf`), so under `--logic=main` the Logic lane stays empty and a path's hops on `main`
name no tick. `Atchung.tick(name, arrival)` could take a name and write it in the row (`#12 logic 2 rounds ...`);
the framework would name its own (`logic`, `widgets`, `senders`), and FN-8 would go with it. Fixed upstream
2026-11-02 (R7): atchung's `Tick.named` (`480953a`) puts the name after the number, and the framework names the
logic's tick `logic` (`e4e4f55`). The widgets' stays unnamed, which is enough to tell the two apart.

## FN-17 · The logic lane's thread parks between ticks without a row 🔬 (fixed upstream)

The threads panel (R5) cuts each clock's turn into working, waiting, resting and unaccounted by its thread's rows.
The main loop's park is a row, `wait for events` (an idle zone), so the main thread's rest is measured. The logic
lane's coordinator parks in `Logic.runUntilStopped` (`LockSupport.parkNanos`, at most `PARK_NANOS` before it looks
again) and writes nothing, and the unpark that ends it (`Logic.arrived`) writes nothing either. From the rows, a tick's
turn is its `tick` zone and then a gap that could be rest, a stall or the scheduler, and the wakes and their latency
cannot be read. Worked around by calling that gap unaccounted, never resting, with the note "its park writes no row
(FN-17)", and by leaving the logic lane's wakes blank. The park could be `Probe.idleZone(Lane.BUS, "logic park")`
around the `parkNanos`, as the loop's is, and `arrived` a `wake` mark naming the thread it unparked; the ledger would
then read the logic lane's rest and wakes exactly as it reads the main thread's. Fixed upstream 2026-11-02 (R7,
vexelray-framework `e4e4f55`), as proposed: the park is that idle zone, and the arrival that ends one writes a single
`wake`, `message on logic`, on the thread that ended it.

## FN-18 · A satellite window's view has its `@Draw` asked by the main window's tree 🔬

The graph panel's dots (moving along each busy arrow, as many a second as its sender writes) run from `View`'s
`@Draw`, offsetting and fading a floating node a dot. The graph window's `GraphView` is a `@View` of its own over the
satellite's tree, so it was given the same: a `Dots` state field and a `@Draw` placing nodes on its stage. The processor
accepted it, but the generated wiring registers it as `Views.draw(shell.gui(), ...)`, the main window's tree, whatever
window the view's nodes are in. Measured with `ottermate --window graph shot` (2026-11-10): every dot stood at the
stage's top left, where `floatAt(0, 0)` put it, in two shots a second apart; the main window's dots moved. The main
tree's `Drawn` ignores a node that is not in it, as its javadoc says, so nothing set them. Worked around by leaving the
graph window without dots: its arrows still say their rate and how long ago they were last written. The wiring could
register a view's `@Draw` with the tree of the window its constructor's window parameter is (the satellite's
`Window.gui()`), as it already knows which `GraphWindow` it builds the view from; or the processor could refuse a
`@Draw` on a view whose nodes it cannot place, rather than accept one that is never seen. This is also what TODO's
"easing the graph window's zoom needs a `@Draw` for a second window's view" will meet.
