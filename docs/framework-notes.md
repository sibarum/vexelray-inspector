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

## FN-8 · Under `--logic=main`, a logic tick cannot be told from the widgets' 🔬

The timeline's Logic lane is atchung's `tick` zone (`Tick.java`, `Lane.BUS`), and the GUI's widget tick writes the same
zone on `main`. On a logic lane the thread tells them apart (`vexel-logic` against `main`), so `Model` takes every
`tick` row not on `main`. Under `--logic=main` both are on `main`, with nothing else in the row to separate them (the
link's `Row` has no cause, and the zone's detail is only its length), so the Logic lane and the readouts' ticks are
empty there rather than wrong. Seen in a traced run (2026-10-30): `vexel-logic` and `main` each write about 100 `tick`
rows a second. A tick's own row, numbered and named for its `Tick` (design.md 7½, the row per tick), would close it; so
would the zone naming its tick (`tick logic`, `tick widgets`). The mode itself is also not said (design.md section 7).

## FN-9 · Automation alone records no ticks and no GPU 🔬

An application started with `--automation=0` and no `-Dprobe` records the `input`, `frame`, `layout` and `app` lanes
only (its startup line says so), so attached to one, the Logic, Drawing and GPU lanes stay empty with no word of why;
`-Dprobe=all` is needed, as README and the status line say. Not worked around further: the Inspector cannot tell "this
run records no ticks" from "this run had none". The link's `StreamOpened` saying which lanes the run records would let a
lane say "not recorded" instead of showing nothing, which any client of the stream would want.

## FN-10 · A message's row names no sender, no receiver and no transport 🔬

A message published on the bus writes one row on the `bus` lane whose kind is its type and whose thread is the one
that published it. That is all the details panel can say of it: not which component sent it (the cause would link it,
and the recorder drops the cause, design.md section 7), not who received it (bus graph version 1 lists `@Subscribe`
mailboxes only, and most 0.2 receivers are `Inbox`es), and not how it travelled (state or edge topic, which mailbox,
its lane and capacity), which version 1 does not say either. Worked around by saying so in the panel: From is the
thread, and To and Transport say "not recorded yet". Bus graph version 2 (design.md 7½) and the kept cause would fill
all three; the row naming its topic would let the Inspector match it to the graph.

## FN-4 · No verb closes an application 🔬

A run started outside `--launch` is closed by clicking where the title bar draws its close button
(`click 1017,16`): the caption buttons are not nodes, so `find close` finds nothing and a script has to know pixel
positions. A `close` verb, or landmarks on the caption buttons, would make it a name.

