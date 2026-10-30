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

## FN-4 · No verb closes an application 🔬

A run started outside `--launch` is closed by clicking where the title bar draws its close button
(`click 1017,16`): the caption buttons are not nodes, so `find close` finds nothing and a script has to know pixel
positions. A `close` verb, or landmarks on the caption buttons, would make it a name.

