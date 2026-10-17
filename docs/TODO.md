# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **The live source.** A `StreamSource` beside `ReplaySource`: connect by port, send `stream all`, and feed each
      line through `RowFormat` to the sink. Waits on flight-recorder.md §9 step 5. `RowSource`'s javadoc is the
      spec.
- [ ] **Attach by launching**: `--launch <command>`, reading `automation: localhost:<port>` from the child's stdout
      as `ottermate --launch` does, and stopping the child (and a `mvn` wrapper's grandchild) on close.
- [ ] **The recorder's own cost**, shown from day one (roadmap M1). Needs the recorder's `snap` rows.
- [ ] **A gap no park covers** is a stall (automation.md §4, *Reading it*). The escapement knows the budget and the
      rest; when a rest outlasts its budget by more than a frame, the wheel should say so rather than just rest.
- [ ] The frame number: `Row.presentedFrame()` reads `#<n>` from a `frame.present` detail; once the stream stamps
      every row, the escapement should prefer the stamp.

## Later

- [ ] An icon of its own: `src/main/rc/vexelray-inspector.ico` is still the framework's "new app" mark. A balance
      wheel would do.
- [ ] Opening a recording from the window (a file picker through `Dialogs.openFile`), not only from the command
      line.
- [ ] A native build, both editions, checked with `ottermate` against the debug one.
