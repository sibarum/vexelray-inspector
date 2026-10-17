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
with that framing, so the retrospective has a candidate rather than a complaint.

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
