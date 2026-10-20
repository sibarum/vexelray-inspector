# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **The recorder's own cost**, shown from day one (roadmap M1). The link's `Snap` carries the loop and ring
      counters but no cost yet, and `LinkSource` does not read snapshots: both ends need it.

## Restart in debug mode

- [ ] **Follow a restart**: an Inspector attached to an application that restarts (into or out of a debug run) says
      `went away` and shows the picker, and *Restart in debug mode* opens a second Inspector. The old one should
      follow the new process instead. The discovery file would have to say which pid a run replaces (it has
      `--restart.after=<pid>`, and does not write it down), so this needs a `replaces=` fact upstream first.

## The bus (M3)

- [ ] **See a declared graph live**: no application declares `@Subscribe`/`@Publishes` yet, so the circuit has only
      been seen live with the framework chip (the calculator). The framework's components witness answers `bus graph`,
      but vexelray-framework's acceptance harness is broken on develop (its own TODO); once it runs, launch the witness
      under `--launch` and look.
- [ ] **A real-time scale on the board**: a tick every 1 or 10 ms of the target's time, spread apart while a burst is
      stretched and bunched while the clock catches up, so a stretched burst still reads as its true length.
- [ ] **Records per build**: keep each run's biggest burst in the history, so `NEW RECORD` can mean "bigger than any
      run of this build" and a build whose bursts grew is plain from the strip.
- [ ] **Strings inside the frame**: the show runs on the timeline inside the frame, and the readouts, the governor
      and the combo counter format a string when they change. Small and bounded, but against the frame-loop rule;
      digits drawn from a fixed set of glyphs, or text written only on change of the shown value, would remove it.
- [ ] **`settle` never answers on the Inspector**: the picker's one-second discovery timer keeps the timeline busy,
      so `ottermate settle`, `--size` and `resize` time out (the resize still happens). Scripts wait on landmarks.
