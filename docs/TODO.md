# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **The recorder's own cost**, shown from day one (roadmap M1). The link's `Snap` carries the loop and ring
      counters but no cost yet, and `LinkSource` does not read snapshots: both ends need it.

## Frames and rates

- [ ] **The frame readout and the history count turns, not frames**: `Escapement` and `Session` count every
      `frame.present`, so `(43 seen)` and the history's frames include turns that drew nothing (FN-14). The graph and
      the tachometer count frames drawn; the two should agree on what a frame is.
- [ ] **A finished replay never lets the timeline go quiet**: `effect@frames` "reads something varying until
      forever", so `settle` and `zoom` wait out their timeout once a `--replay.loop=false` run has ended. Already so at
      069f1aa; found, not chased.

## The tape (M2)

- [ ] **A replay looping while paused clears the graph on going on**: the graph's counts are reset when the new run
      begins, and the board catches up when the pause ends. The tape's frozen copy is not affected.
- [ ] **Remember the reach chosen**: the window's choice lasts the run; the next starts from `--tape.seconds` again.
- [ ] **A reach chosen while paused is drawn on going on**: the frozen strip keeps the reach it was frozen with, while
      the reach line above it already names the new one.
- [ ] **The movement follows the cursor**: while paused, the escapement and the readouts show the selected moment,
      rebuilt from the tape (roadmap: step through the rings frame by frame).
- [ ] **Collapse it**: the strip adds about 9em under the movement, in a window that is already too short (below).

## Restart in debug mode

- [ ] **Follow a restart**: an Inspector attached to an application that restarts (into or out of a debug run) says
      `went away` and shows the picker, and *Restart in debug mode* opens a second Inspector. The old one should
      follow the new process instead. The discovery file would have to say which pid a run replaces (it has
      `--restart.after=<pid>`, and does not write it down), so this needs a `replaces=` fact upstream first.

## The component graph (M3)

- [ ] **Hold a flow's messages**: selecting an arrow or a table row keeps its last messages, with their payloads, on
      screen until it is let go (docs/component-graph.md, *Not in this slice*).
- [ ] **More than eight flows**: the table shows eight, and past that its last row only counts the rest. A real
      application has more; the table needs to scroll, or to fold flows by part.
- [ ] **Arrows between components hide behind the boxes between them**: the lanes stand in one column, so an arrow
      from the fourth to the second runs under the third. Lay the column out by flow, or route those arrows round it.
- [ ] **See it on an application with components, live**: the physics lanes of the rigid and fluid sim demos are real
      `@Component`s with named mailboxes (no `@Publishes`). Launch one under `--launch` and look. The made-up
      `samples/circuit-bus.csv` with `samples/circuit-graph.txt` is the only component run seen so far.
- [ ] **The window is too short for the graph and the movement**: they need about 58em, and a window size saved from
      before (860 px), or a screen under about 1080 px, opens shorter, so the movement scrolls under the graph. The
      minimum is no fix: it was 58em for a while, and a window under it scrolled twice, the root as well as the body
      (two scrollbars, the status bar scrolled away). It is back to 35em, and the body is the one scroller. A shorter
      graph is.
- [ ] **Strings inside the frame**: the show runs on the timeline inside the frame, and the readouts, the parts'
      counts, the flow table and the governor format a string when they change, at most ten times a second. Small and
      bounded, but against the frame-loop rule; digits drawn from a fixed set of glyphs would remove it.
- [ ] **`settle` never answers on the Inspector**: the picker's one-second discovery timer keeps the timeline busy,
      so `ottermate settle`, `--size` and `resize` time out (the resize still happens). Scripts wait on landmarks.
