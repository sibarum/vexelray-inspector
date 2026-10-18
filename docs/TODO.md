# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **Attach by launching**: `--launch <command>`, reading `automation: localhost:<port>` from the child's stdout
      as `ottermate --launch` does (or waiting for its discovery file), attaching to it, and stopping the child (and
      a `mvn` wrapper's grandchild) on close.
- [ ] **The recorder's own cost**, shown from day one (roadmap M1). The link's `Snap` carries the loop and ring
      counters but no cost yet, and `LinkSource` does not read snapshots: both ends need it.
- [ ] **A gap no park covers** is a stall (automation.md §4, *Reading it*). The escapement knows the budget and the
      rest; when a rest outlasts its budget by more than a frame, the wheel should say so rather than just rest.
- [ ] **Lost rows.** `LinkSource` delivers a gap as a `stream`/`lost` row, and nothing reads that lane yet. The
      wheel should show that it missed beats rather than run on as if it had not.
- [ ] **The release edition with the link.** `vexelray-gui-inspector-link` brings `vexelray-gui-automation` on the
      class path, which the release profile puts at test scope. The JVM run is fine (`LinkClient` does not touch
      it), but a release native image has not been tried. A client-only module split out of the link would settle
      it.

## Later

- [ ] An icon of its own: `src/main/rc/vexelray-inspector.ico` is still the framework's "new app" mark. A balance
      wheel would do.
- [ ] Opening a recording from the window (a file picker through `Dialogs.openFile`), not only from the command
      line.
- [ ] A native build, both editions, checked with `ottermate` against the debug one.
