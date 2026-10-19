# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **The recorder's own cost**, shown from day one (roadmap M1). The link's `Snap` carries the loop and ring
      counters but no cost yet, and `LinkSource` does not read snapshots: both ends need it.

## The bus (M3)

- [ ] **The graph over the link**: on attaching, ask `bus graph` (docs/bus-circuit.md §4) and lay the circuit out from
      the answer, instead of `--bus.graph=<file>`. Needs the vexelray-gui and framework halves; the board is built with
      the window today, so a graph per attach means rebuilding its pins and paths on the timeline.
- [ ] **A real-time scale on the board**: a tick every 1 or 10 ms of the target's time, spread apart while a burst is
      stretched and bunched while the clock catches up, so a stretched burst still reads as its true length.
- [ ] **Records per build**: keep each run's biggest burst in the history, so `NEW RECORD` can mean "bigger than any
      run of this build" and a build whose bursts grew is plain from the strip.
- [ ] **Strings inside the frame**: the show runs on the timeline inside the frame, and the readouts, the governor
      and the combo counter format a string when they change. Small and bounded, but against the frame-loop rule;
      digits drawn from a fixed set of glyphs, or text written only on change of the shown value, would remove it.
- [ ] **`settle` never answers on the Inspector**: the picker's one-second discovery timer keeps the timeline busy,
      so `ottermate settle`, `--size` and `resize` time out (the resize still happens). Scripts wait on landmarks.
