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
